/*
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.reteoo;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.List;
import java.util.Map;

import org.drools.RuleBaseConfiguration;
import org.drools.base.DroolsQuery;
import org.drools.base.InternalViewChangedEventListener;
import org.drools.base.extractors.ArrayElementReader;
import org.drools.common.BaseNode;
import org.drools.common.InternalFactHandle;
import org.drools.common.InternalWorkingMemory;
import org.drools.common.LeftTupleIterator;
import org.drools.common.Memory;
import org.drools.common.MemoryFactory;
import org.drools.common.PropagationContextImpl;
import org.drools.common.QueryElementFactHandle;
import org.drools.common.UpdateContext;
import org.drools.core.util.AbstractBaseLinkedListNode;
import org.drools.core.util.index.RightTupleList;
import org.drools.marshalling.impl.PersisterHelper;
import org.drools.marshalling.impl.ProtobufInputMarshaller.QueryElementContext;
import org.drools.marshalling.impl.ProtobufInputMarshaller.TupleKey;
import org.drools.marshalling.impl.ProtobufMessages;
import org.drools.reteoo.ReteooWorkingMemory.QueryInsertAction;
import org.drools.reteoo.ReteooWorkingMemory.QueryResultInsertAction;
import org.drools.reteoo.ReteooWorkingMemory.QueryResultRetractAction;
import org.drools.reteoo.ReteooWorkingMemory.QueryResultUpdateAction;
import org.drools.reteoo.ReteooWorkingMemory.QueryRetractAction;
import org.drools.reteoo.ReteooWorkingMemory.QueryUpdateAction;
import org.drools.reteoo.builder.BuildContext;
import org.drools.rule.Declaration;
import org.drools.rule.QueryElement;
import org.drools.rule.Rule;
import org.drools.spi.PropagationContext;
import org.kie.runtime.rule.Variable;

public class QueryElementNode extends LeftTupleSource
    implements
    LeftTupleSinkNode,
    MemoryFactory {

    private LeftTupleSinkNode previousTupleSinkNode;
    private LeftTupleSinkNode nextTupleSinkNode;

    private QueryElement      queryElement;

    private boolean           tupleMemoryEnabled;

    private boolean           openQuery;

    public QueryElementNode() {
        // for serialization
    }

    public QueryElementNode(final int id,
                            final LeftTupleSource tupleSource,
                            final QueryElement queryElement,
                            final boolean tupleMemoryEnabled,
                            final boolean openQuery,
                            final BuildContext context) {
        super( id,
               context.getPartitionId(),
               context.getRuleBase().getConfiguration().isMultithreadEvaluation() );
        setLeftTupleSource(tupleSource);
        this.queryElement = queryElement;
        this.tupleMemoryEnabled = tupleMemoryEnabled;
        this.openQuery = openQuery;
        initMasks( context, tupleSource );
    }

    public void readExternal(ObjectInput in) throws IOException,
                                            ClassNotFoundException {
        super.readExternal( in );
        queryElement = (QueryElement) in.readObject();
        tupleMemoryEnabled = in.readBoolean();
        openQuery = in.readBoolean();
    }

    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal( out );
        out.writeObject( queryElement );
        out.writeBoolean( tupleMemoryEnabled );
        out.writeBoolean( openQuery );
    }

    public void updateSink(final LeftTupleSink sink,
                           final PropagationContext context,
                           final InternalWorkingMemory workingMemory) {
        LeftTupleIterator it = LeftTupleIterator.iterator( workingMemory, this );
        
        for ( LeftTuple leftTuple =  ( LeftTuple ) it.next(); leftTuple != null; leftTuple =  ( LeftTuple ) it.next() ) {
            LeftTuple childLeftTuple = leftTuple.getFirstChild();
            while ( childLeftTuple != null ) {
                RightTuple rightParent = childLeftTuple.getRightParent();            
                sink.assertLeftTuple( sink.createLeftTuple( leftTuple, rightParent, childLeftTuple, null, sink, true ),
                                      context,
                                      workingMemory );  
                
                while ( childLeftTuple != null && childLeftTuple.getRightParent() == rightParent ) {
                    // skip to the next child that has a different right parent
                    childLeftTuple = childLeftTuple.getLeftParentNext();
                }
            }
        }
    }

    protected void doRemove(RuleRemovalContext context,
                            ReteooBuilder builder,
                            BaseNode node,
                            InternalWorkingMemory[] workingMemories) {
        if ( !node.isInUse() ) {
            removeTupleSink( (LeftTupleSink) node );
        }

        this.leftInput.remove( context,
                                 builder,
                                 this,
                                 workingMemories );
    }

    public void attach( BuildContext context ) {
        this.leftInput.addTupleSink( this, context );
        if (context == null) {
            return;
        }

        for ( InternalWorkingMemory workingMemory : context.getWorkingMemories() ) {
            final PropagationContext propagationContext = new PropagationContextImpl( workingMemory.getNextPropagationIdCounter(),
                                                                                      PropagationContext.RULE_ADDITION,
                                                                                      null,
                                                                                      null,
                                                                                      null );
            this.leftInput.updateSink( this,
                                         propagationContext,
                                         workingMemory );
        }
    }

    public void networkUpdated(UpdateContext updateContext) {
        this.leftInput.networkUpdated(updateContext);
    }

    public short getType() {
        return NodeTypeEnums.UnificationNode;
    }

    public boolean isLeftTupleMemoryEnabled() {
        return false;
    }

    public QueryElement getQueryElement() {
        return queryElement;
    }

    public boolean isOpenQuery() {
        return openQuery;
    }

    public void assertLeftTuple(LeftTuple leftTuple,
                                PropagationContext context,
                                InternalWorkingMemory workingMemory) {
        // the next call makes sure this node's memory is initialised
        workingMemory.getNodeMemory( this );
        
        InternalFactHandle handle = createFactHandle( context, 
                                                      workingMemory, 
                                                      leftTuple ); 

        DroolsQuery queryObject = createDroolsQuery( leftTuple,
                                                     handle,
                                                     workingMemory );

        QueryInsertAction action = new QueryInsertAction( context,
                                                          handle,
                                                          leftTuple,
                                                          this );
        queryObject.setAction( action ); // this is necessary as queries can be re-entrant, so we can check this before re-sheduling
                                         // another action in the modify section. Make sure it's nulled after the action is done
                                         // i.e. scheduling an insert and then an update, before the insert is executed
        context.addInsertAction( action );

    }

    @SuppressWarnings("unchecked")
    private InternalFactHandle createFactHandle(final PropagationContext context,
                                                final InternalWorkingMemory workingMemory,
                                                final LeftTuple leftTuple ) {
        InternalFactHandle handle = null;
        ProtobufMessages.FactHandle _handle = null;
        if( context.getReaderContext() != null ) {
            Map<TupleKey, QueryElementContext> map = (Map<TupleKey, QueryElementContext>) context.getReaderContext().nodeMemories.get( getId() );
            if( map != null ) {
                _handle = map.get( PersisterHelper.createTupleKey( leftTuple ) ).handle;
            }
        }
        if( _handle != null ) {
            // create a handle with the given id
            handle = workingMemory.getFactHandleFactory().newFactHandle( _handle.getId(),
                                                                         null,
                                                                         _handle.getRecency(),
                                                                         null,
                                                                         workingMemory,
                                                                         workingMemory ); 
        } else {
            handle = workingMemory.getFactHandleFactory().newFactHandle( null,
                                                                         null,
                                                                         workingMemory,
                                                                         workingMemory ); 
        }
        return handle;
    }
    
    public DroolsQuery createDroolsQuery(LeftTuple leftTuple,
                                         InternalFactHandle handle,
                                         InternalWorkingMemory workingMemory) {
        Object[] argTemplate = this.queryElement.getArgTemplate(); // an array of declr, variable and literals
        Object[] args = new Object[argTemplate.length]; // the actual args, to be created from the  template

        // first copy everything, so that we get the literals. We will rewrite the declarations and variables next
        System.arraycopy( argTemplate,
                          0,
                          args,
                          0,
                          args.length );

        int[] declIndexes = this.queryElement.getDeclIndexes();

        for ( int i = 0, length = declIndexes.length; i < length; i++ ) {
            Declaration declr = (Declaration) argTemplate[declIndexes[i]];

            Object tupleObject = leftTuple.get( declr ).getObject();

            Object o;

            if ( tupleObject instanceof DroolsQuery ) {
                // If the query passed in a Variable, we need to use it
                ArrayElementReader arrayReader = (ArrayElementReader) declr.getExtractor();
                if ( ((DroolsQuery) tupleObject).getVariables()[arrayReader.getIndex()] != null ) {
                    o = Variable.v;
                } else {
                    o = declr.getValue( workingMemory,
                                        tupleObject );
                }
            } else {
                o = declr.getValue( workingMemory,
                                    tupleObject );
            }

            args[declIndexes[i]] = o;
        }

        int[] varIndexes = this.queryElement.getVariableIndexes();
        for ( int i = 0, length = varIndexes.length; i < length; i++ ) {
            if ( argTemplate[varIndexes[i]] == Variable.v ) {
                // Need to check against the arg template, as the varIndexes also includes re-declared declarations
                args[varIndexes[i]] = Variable.v;
            }
        }

        UnificationNodeViewChangedEventListener collector = createCollector( leftTuple, varIndexes, this.tupleMemoryEnabled );
        
        boolean executeAsOpenQuery = openQuery;
        if ( executeAsOpenQuery ) {
            // There is no point in doing an open query if the caller is a non-open query.
            Object object = ((InternalFactHandle) leftTuple.get( 0 )).getObject();
            if ( object instanceof DroolsQuery && !((DroolsQuery) object).isOpen() ) {
                executeAsOpenQuery = false;
            }          
        }

        DroolsQuery queryObject = new DroolsQuery( this.queryElement.getQueryName(),
                                                   args,
                                                   collector,
                                                   executeAsOpenQuery );

        collector.setFactHandle( handle );

        handle.setObject( queryObject );

        leftTuple.setObject( handle ); // so it can be retracted later and destroyed

        return queryObject;
    }

    protected UnificationNodeViewChangedEventListener createCollector( LeftTuple leftTuple, int[] varIndexes, boolean tupleMemoryEnabled ) {
        return new UnificationNodeViewChangedEventListener( leftTuple,
                varIndexes,
                this,
                tupleMemoryEnabled );
    }

    public void retractLeftTuple(LeftTuple leftTuple,
                                 PropagationContext context,
                                 InternalWorkingMemory workingMemory) {
        QueryRetractAction action = new QueryRetractAction( context,
                                                            leftTuple,
                                                            this );
        context.addInsertAction( action );
    }

    public void modifyLeftTuple(LeftTuple leftTuple,
                                PropagationContext context,
                                InternalWorkingMemory workingMemory) {
        boolean executeAsOpenQuery = openQuery;
        if ( executeAsOpenQuery ) {
            // There is no point in doing an open query if the caller is a non-open query.
            Object object = ((InternalFactHandle) leftTuple.get( 0 )).getObject();
            if ( object instanceof DroolsQuery && !((DroolsQuery) object).isOpen() ) {
                executeAsOpenQuery = false;
            }          
        }        
        
        if (  !executeAsOpenQuery ) {
            // Was never open so execute as a retract + assert
            if ( leftTuple.getFirstChild() != null ) {
                this.sink.propagateRetractLeftTuple( leftTuple,
                                                     context,
                                                     workingMemory );
            }
            assertLeftTuple( leftTuple,
                             context,
                             workingMemory );
            return;
        }

        InternalFactHandle handle = (InternalFactHandle) leftTuple.getObject();
        DroolsQuery queryObject = (DroolsQuery) handle.getObject();
        if ( queryObject.getAction() != null ) {
            // we already have an insert scheduled for this query, but have re-entered it
            // do nothing
            return;
        }

        Object[] argTemplate = this.queryElement.getArgTemplate(); // an array of declr, variable and literals
        Object[] args = new Object[argTemplate.length]; // the actual args, to be created from the  template

        // first copy everything, so that we get the literals. We will rewrite the declarations and variables next
        System.arraycopy( argTemplate,
                          0,
                          args,
                          0,
                          args.length );

        int[] declIndexes = this.queryElement.getDeclIndexes();

        for ( int i = 0, length = declIndexes.length; i < length; i++ ) {
            Declaration declr = (Declaration) argTemplate[declIndexes[i]];

            Object tupleObject = leftTuple.get( declr ).getObject();

            Object o;

            if ( tupleObject instanceof DroolsQuery ) {
                // If the query passed in a Variable, we need to use it
                ArrayElementReader arrayReader = (ArrayElementReader) declr.getExtractor();
                if ( ((DroolsQuery) tupleObject).getVariables()[arrayReader.getIndex()] != null ) {
                    o = Variable.v;
                } else {
                    o = declr.getValue( workingMemory,
                                        tupleObject );
                }
            } else {
                o = declr.getValue( workingMemory,
                                    tupleObject );
            }

            args[declIndexes[i]] = o;
        }

        int[] varIndexes = this.queryElement.getVariableIndexes();
        for ( int i = 0, length = varIndexes.length; i < length; i++ ) {
            if ( argTemplate[varIndexes[i]] == Variable.v ) {
                // Need to check against the arg template, as the varIndexes also includes re-declared declarations
                args[varIndexes[i]] = Variable.v;
            }
        }

        queryObject.setParameters( args );
        ((UnificationNodeViewChangedEventListener) queryObject.getQueryResultCollector()).setVariables( varIndexes );

        QueryUpdateAction action = new QueryUpdateAction( context,
                                                          handle,
                                                          leftTuple,
                                                          this );
        context.addInsertAction( action );
    }

    public LeftTupleSource getLeftTupleSource() {
        return this.leftInput;
    }

    public void setLeftTupleMemoryEnabled(boolean tupleMemoryEnabled) {
        this.tupleMemoryEnabled = tupleMemoryEnabled;
    }

    /**
     * Returns the next node
     * @return
     *      The next TupleSinkNode
     */
    public LeftTupleSinkNode getNextLeftTupleSinkNode() {
        return this.nextTupleSinkNode;
    }

    /**
     * Sets the next node
     * @param next
     *      The next TupleSinkNode
     */
    public void setNextLeftTupleSinkNode(final LeftTupleSinkNode next) {
        this.nextTupleSinkNode = next;
    }

    /**
     * Returns the previous node
     * @return
     *      The previous TupleSinkNode
     */
    public LeftTupleSinkNode getPreviousLeftTupleSinkNode() {
        return this.previousTupleSinkNode;
    }

    /**
     * Sets the previous node
     * @param previous
     *      The previous TupleSinkNode
     */
    public void setPreviousLeftTupleSinkNode(final LeftTupleSinkNode previous) {
        this.previousTupleSinkNode = previous;
    }

    public static class UnificationNodeViewChangedEventListener
        implements
        InternalViewChangedEventListener {

        private LeftTuple          leftTuple;

        protected QueryElementNode   node;

        private InternalFactHandle factHandle;

        private int[]              variables;

        private boolean            tupleMemoryEnabled;

        public UnificationNodeViewChangedEventListener(LeftTuple leftTuple,
                                                       int[] variables,
                                                       QueryElementNode node,
                                                       boolean tupleMemoryEnabled) {
            this.leftTuple = leftTuple;
            this.variables = variables;
            this.node = node;
            this.tupleMemoryEnabled = tupleMemoryEnabled;
        }

        public InternalFactHandle getFactHandle() {
            return factHandle;
        }

        public void setFactHandle(InternalFactHandle factHandle) {
            this.factHandle = factHandle;
        }

        public void setVariables(int[] variables) {
            this.variables = variables;
        }

        public void rowAdded(final Rule rule,
                             LeftTuple resultLeftTuple,
                             PropagationContext context,
                             InternalWorkingMemory workingMemory) {

            QueryTerminalNode node = (QueryTerminalNode) resultLeftTuple.getLeftTupleSink();
            Declaration[] decls = node.getDeclarations();
            DroolsQuery query = (DroolsQuery) this.factHandle.getObject();
            Object[] objects = new Object[query.getElements().length];

            Declaration decl;
            for ( int i = 0, length = this.variables.length; i < length; i++ ) {
                decl = decls[this.variables[i]];
                objects[this.variables[i]] = decl.getValue( workingMemory,
                                                            resultLeftTuple.get( decl ).getObject() );
            }

            QueryElementFactHandle resultHandle = createQueryResultHandle( context,
                                                                           workingMemory, 
                                                                           objects );
            
            RightTuple rightTuple = createResultRightTuple( resultHandle, resultLeftTuple, query.isOpen() );

            this.node.getSinkPropagator().createChildLeftTuplesforQuery( this.leftTuple,
                                                                         rightTuple,
                                                                         true, // this must always be true, otherwise we can't 
                                                                               // find the child tuples to iterate for evaluating the query results
                                                                         query.isOpen() );

            RightTupleList rightTuples = query.getResultInsertRightTupleList();
            if ( rightTuples == null ) {
                rightTuples = new RightTupleList();
                query.setResultInsertRightTupleList( rightTuples );
                QueryResultInsertAction evalAction = new QueryResultInsertAction( context,
                                                                                  this.factHandle,
                                                                                  leftTuple,
                                                                                  this.node );
                context.getQueue2().addFirst( evalAction );
            }

            rightTuples.add( rightTuple );
        }

        protected RightTuple createResultRightTuple( QueryElementFactHandle resultHandle, LeftTuple resultLeftTuple, boolean open ) {
            RightTuple rightTuple = new RightTuple( resultHandle );
            if ( open ) {
                rightTuple.setLeftTuple( resultLeftTuple );
                resultLeftTuple.setObject( rightTuple );

            }
            return rightTuple;
        }

        @SuppressWarnings("unchecked")
        private QueryElementFactHandle createQueryResultHandle(PropagationContext context,
                                                               InternalWorkingMemory workingMemory,
                                                               Object[] objects) {
            QueryElementFactHandle handle = null;
            ProtobufMessages.FactHandle _handle = null;
            if( context.getReaderContext() != null ) {
                Map<TupleKey, QueryElementContext> map = (Map<TupleKey, QueryElementContext>) context.getReaderContext().nodeMemories.get( node.getId() );
                if( map != null ) {
                    QueryElementContext _context = map.get( PersisterHelper.createTupleKey( leftTuple ) );
                    if( _context != null ) {
                        _handle = _context.results.removeFirst();
                    }
                }
            }
            if( _handle != null ) {
                // create a handle with the given id
                handle = new QueryElementFactHandle( objects,
                                                     _handle.getId(),
                                                     _handle.getRecency() );
            } else {
                handle = new QueryElementFactHandle( objects,
                                                     workingMemory.getFactHandleFactory().getAtomicId().incrementAndGet(),
                                                     workingMemory.getFactHandleFactory().getAtomicRecency().incrementAndGet() );
            }
            return handle;
        }

        public void rowRemoved(final Rule rule,
                               final LeftTuple resultLeftTuple,
                               final PropagationContext context,
                               final InternalWorkingMemory workingMemory) {
            RightTuple rightTuple = (RightTuple) resultLeftTuple.getObject();
            rightTuple.setLeftTuple( null );
            resultLeftTuple.setObject( null );

            DroolsQuery query = (DroolsQuery) this.factHandle.getObject();

            RightTupleList rightTuples = query.getResultRetractRightTupleList();
            if ( rightTuples == null ) {
                rightTuples = new RightTupleList();
                query.setResultRetractRightTupleList( rightTuples );
                QueryResultRetractAction retractAction = new QueryResultRetractAction( context,
                                                                                       this.factHandle,
                                                                                       leftTuple,
                                                                                       this.node );
                context.getQueue2().addFirst( retractAction );
            }
            if ( rightTuple.getMemory() != null ) {
                throw new RuntimeException();
            }
            rightTuples.add( rightTuple );
        }

        public void rowUpdated(final Rule rule,
                               final LeftTuple resultLeftTuple,
                               final PropagationContext context,
                               final InternalWorkingMemory workingMemory) {
            RightTuple rightTuple = (RightTuple) resultLeftTuple.getObject();
            if ( rightTuple.getMemory() != null ) {
                // Already sheduled as an insert
                return;
            }

            rightTuple.setLeftTuple( null );
            resultLeftTuple.setObject( null );

            // We need to recopy everything back again, as we don't know what has or hasn't changed
            QueryTerminalNode node = (QueryTerminalNode) resultLeftTuple.getLeftTupleSink();
            Declaration[] decls = node.getDeclarations();
            InternalFactHandle rootHandle = resultLeftTuple.get( 0 );
            DroolsQuery query = (DroolsQuery) rootHandle.getObject();

            Object[] objects = new Object[query.getElements().length];

            Declaration decl;
            for ( int i = 0, length = this.variables.length; i < length; i++ ) {
                decl = decls[this.variables[i]];
                objects[this.variables[i]] = decl.getValue( workingMemory,
                                                            resultLeftTuple.get( decl ).getObject() );
            }

            QueryElementFactHandle handle = (QueryElementFactHandle) rightTuple.getFactHandle();

            handle.setRecency( workingMemory.getFactHandleFactory().getAtomicRecency().incrementAndGet() );
            handle.setObject( objects );

            if ( query.isOpen() ) {
                rightTuple.setLeftTuple( resultLeftTuple );
                resultLeftTuple.setObject( rightTuple );
            }

            // Don't need to recreate child links, as they will already be there form the first "add"

            RightTupleList rightTuples = query.getResultUpdateRightTupleList();
            if ( rightTuples == null ) {
                rightTuples = new RightTupleList();
                query.setResultUpdateRightTupleList( rightTuples );
                QueryResultUpdateAction updateAction = new QueryResultUpdateAction( context,
                                                                                    this.factHandle,
                                                                                    leftTuple,
                                                                                    this.node );
                context.getQueue2().addFirst( updateAction );
            }
            rightTuples.add( rightTuple );
        }

        public List< ? extends Object> getResults() {
            throw new UnsupportedOperationException( getClass().getCanonicalName() + " does not support the getResults() method." );
        }

        public LeftTuple getLeftTuple() {
            return leftTuple;
        }

    }

    public LeftTuple createLeftTuple(InternalFactHandle factHandle,
                                     LeftTupleSink sink,
                                     boolean leftTupleMemoryEnabled) {
        return new QueryElementNodeLeftTuple( factHandle,
                                              sink,
                                              leftTupleMemoryEnabled );
    }

    public LeftTuple createLeftTuple(LeftTuple leftTuple,
                                     LeftTupleSink sink,
                                     boolean leftTupleMemoryEnabled) {
        return new QueryElementNodeLeftTuple( leftTuple,
                                              sink,
                                              leftTupleMemoryEnabled );
    }

    public LeftTuple createLeftTuple(LeftTuple leftTuple,
                                     RightTuple rightTuple,
                                     LeftTupleSink sink) {
        return new QueryElementNodeLeftTuple( leftTuple,
                                              rightTuple,
                                              sink );
    }

    public LeftTuple createLeftTuple(LeftTuple leftTuple,
                                     RightTuple rightTuple,
                                     LeftTuple currentLeftChild,
                                     LeftTuple currentRightChild,
                                     LeftTupleSink sink,
                                     boolean leftTupleMemoryEnabled) {
        return new QueryElementNodeLeftTuple( leftTuple,
                                              rightTuple,
                                              currentLeftChild,
                                              currentRightChild,
                                              sink,
                                              leftTupleMemoryEnabled );
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + (openQuery ? 1231 : 1237);
        result = prime * result + ((queryElement == null) ? 0 : queryElement.hashCode());
        result = prime * result + ((leftInput == null) ? 0 : leftInput.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj ) return true;
        if ( obj == null ) return false;
        if ( getClass() != obj.getClass() ) return false;
        QueryElementNode other = (QueryElementNode) obj;
        if ( openQuery != other.openQuery ) return false;
        if ( queryElement == null ) {
            if ( other.queryElement != null ) return false;
        } else if ( !queryElement.equals( other.queryElement ) ) return false;
        if ( leftInput == null ) {
            if ( other.leftInput != null ) return false;
        } else if ( !leftInput.equals( other.leftInput ) ) return false;
        return true;
    }

    public Memory createMemory(RuleBaseConfiguration config) {
        return new QueryElementNodeMemory(this);
    }
    
    public static class QueryElementNodeMemory extends AbstractBaseLinkedListNode<Memory> implements Memory {
        private QueryElementNode node;
        
        private SegmentMemory smem;
        
        public QueryElementNodeMemory(QueryElementNode node) {
            this.node = node;
        }
        
        public QueryElementNode getNode() {
            return this.node;
        }
        
        public short getNodeType() {
            return NodeTypeEnums.QueryElementNode;
        }
        
        public void setSegmentMemory(SegmentMemory smem) {
            this.smem = smem;
        }        
        
        public SegmentMemory getSegmentMemory() {
            return smem;
        }
    }

    protected ObjectTypeNode getObjectTypeNode() {
        return leftInput.getObjectTypeNode();
    }

    @Override
    public LeftTuple createPeer(LeftTuple original) {
        return null;
    }
}
