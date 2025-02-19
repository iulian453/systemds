/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.tugraz.sysds.hops.codegen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map.Entry;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.common.Types.ExecMode;
import org.tugraz.sysds.conf.ConfigurationManager;
import org.tugraz.sysds.conf.DMLConfig;
import org.tugraz.sysds.hops.AggUnaryOp;
import org.tugraz.sysds.hops.Hop;
import org.tugraz.sysds.hops.OptimizerUtils;
import org.tugraz.sysds.hops.Hop.OpOp1;
import org.tugraz.sysds.hops.codegen.cplan.CNode;
import org.tugraz.sysds.hops.codegen.cplan.CNodeCell;
import org.tugraz.sysds.hops.codegen.cplan.CNodeData;
import org.tugraz.sysds.hops.codegen.cplan.CNodeMultiAgg;
import org.tugraz.sysds.hops.codegen.cplan.CNodeOuterProduct;
import org.tugraz.sysds.hops.codegen.cplan.CNodeRow;
import org.tugraz.sysds.hops.codegen.cplan.CNodeTernary;
import org.tugraz.sysds.hops.codegen.cplan.CNodeTpl;
import org.tugraz.sysds.hops.codegen.cplan.CNodeTernary.TernaryType;
import org.tugraz.sysds.hops.codegen.opt.PlanSelection;
import org.tugraz.sysds.hops.codegen.opt.PlanSelectionFuseAll;
import org.tugraz.sysds.hops.codegen.opt.PlanSelectionFuseCostBased;
import org.tugraz.sysds.hops.codegen.opt.PlanSelectionFuseCostBasedV2;
import org.tugraz.sysds.hops.codegen.opt.PlanSelectionFuseNoRedundancy;
import org.tugraz.sysds.hops.codegen.template.CPlanCSERewriter;
import org.tugraz.sysds.hops.codegen.template.CPlanMemoTable;
import org.tugraz.sysds.hops.codegen.template.CPlanOpRewriter;
import org.tugraz.sysds.hops.codegen.template.TemplateBase;
import org.tugraz.sysds.hops.codegen.template.TemplateUtils;
import org.tugraz.sysds.hops.codegen.template.CPlanMemoTable.MemoTableEntry;
import org.tugraz.sysds.hops.codegen.template.CPlanMemoTable.MemoTableEntrySet;
import org.tugraz.sysds.hops.codegen.template.TemplateBase.CloseType;
import org.tugraz.sysds.hops.codegen.template.TemplateBase.TemplateType;
import org.tugraz.sysds.hops.recompile.RecompileStatus;
import org.tugraz.sysds.hops.recompile.Recompiler;
import org.tugraz.sysds.hops.rewrite.HopRewriteUtils;
import org.tugraz.sysds.hops.rewrite.ProgramRewriteStatus;
import org.tugraz.sysds.hops.rewrite.ProgramRewriter;
import org.tugraz.sysds.hops.rewrite.RewriteCommonSubexpressionElimination;
import org.tugraz.sysds.hops.rewrite.RewriteRemoveUnnecessaryCasts;
import org.tugraz.sysds.parser.DMLProgram;
import org.tugraz.sysds.parser.ForStatement;
import org.tugraz.sysds.parser.ForStatementBlock;
import org.tugraz.sysds.parser.FunctionStatement;
import org.tugraz.sysds.parser.FunctionStatementBlock;
import org.tugraz.sysds.parser.IfStatement;
import org.tugraz.sysds.parser.IfStatementBlock;
import org.tugraz.sysds.parser.StatementBlock;
import org.tugraz.sysds.parser.WhileStatement;
import org.tugraz.sysds.parser.WhileStatementBlock;
import org.tugraz.sysds.common.Types.DataType;
import org.tugraz.sysds.runtime.DMLRuntimeException;
import org.tugraz.sysds.runtime.codegen.CodegenUtils;
import org.tugraz.sysds.runtime.codegen.SpoofCellwise.CellType;
import org.tugraz.sysds.runtime.codegen.SpoofRowwise.RowType;
import org.tugraz.sysds.runtime.controlprogram.BasicProgramBlock;
import org.tugraz.sysds.runtime.controlprogram.ForProgramBlock;
import org.tugraz.sysds.runtime.controlprogram.FunctionProgramBlock;
import org.tugraz.sysds.runtime.controlprogram.IfProgramBlock;
import org.tugraz.sysds.runtime.controlprogram.LocalVariableMap;
import org.tugraz.sysds.runtime.controlprogram.Program;
import org.tugraz.sysds.runtime.controlprogram.ProgramBlock;
import org.tugraz.sysds.runtime.controlprogram.WhileProgramBlock;
import org.tugraz.sysds.runtime.instructions.Instruction;
import org.tugraz.sysds.runtime.matrix.data.Pair;
import org.tugraz.sysds.utils.Explain;
import org.tugraz.sysds.utils.Statistics;

public class SpoofCompiler
{
	private static final Log LOG = LogFactory.getLog(SpoofCompiler.class.getName());
	
	//internal configuration flags
	public static final boolean LDEBUG                 = false;
	public static CompilerType JAVA_COMPILER           = CompilerType.JANINO; 
	public static PlanSelector PLAN_SEL_POLICY         = PlanSelector.FUSE_COST_BASED_V2; 
	public static final IntegrationType INTEGRATION    = IntegrationType.RUNTIME;
	public static final boolean RECOMPILE_CODEGEN      = true;
	public static final boolean PRUNE_REDUNDANT_PLANS  = true;
	public static PlanCachePolicy PLAN_CACHE_POLICY    = PlanCachePolicy.CSLH;
	public static final int PLAN_CACHE_SIZE            = 1024; //max 1K classes
	public static final RegisterAlloc REG_ALLOC_POLICY = RegisterAlloc.EXACT_STATIC_BUFF;
	
	public enum CompilerType {
		AUTO,
		JAVAC,
		JANINO,
	}
	
	public enum IntegrationType {
		HOPS,
		RUNTIME,
	}
	
	public enum PlanSelector {
		FUSE_ALL,             //maximal fusion, possible w/ redundant compute
		FUSE_NO_REDUNDANCY,   //fusion without redundant compute 
		FUSE_COST_BASED,      //cost-based decision on materialization points
		FUSE_COST_BASED_V2;   //cost-based decisions on materialization points per consumer, multi aggregates,
		                      //sparsity exploitation, template types, local/distributed operations, constraints
		public boolean isHeuristic() {
			return this == FUSE_ALL
				|| this == FUSE_NO_REDUNDANCY;
		}
		public boolean isCostBased() {
			return this == FUSE_COST_BASED_V2
				|| this == FUSE_COST_BASED;
		}
	}

	public enum PlanCachePolicy {
		CONSTANT, //plan cache, with always compile literals
		CSLH,     //plan cache, with context-sensitive literal replacement heuristic
		NONE;     //no plan cache
		
		public static PlanCachePolicy get(boolean planCache, boolean compileLiterals) {
			return !planCache ? NONE : compileLiterals ? CONSTANT : CSLH;
		}
	}
	
	public enum RegisterAlloc {
		HEURISTIC,           //max vector intermediates, special handling pipelines (always safe)
		EXACT_DYNAMIC_BUFF,  //min number of live vector intermediates, assuming dynamic pooling
		EXACT_STATIC_BUFF,   //min number of live vector intermediates, assuming static array ring buffer
	}
	
	static {
		// for internal debugging only
		if( LDEBUG ) {
			Logger.getLogger("org.tugraz.sysds.hops.codegen")
				  .setLevel((Level) Level.TRACE);
		}
	}
	
	//plan cache for cplan->compiled source to avoid unnecessary codegen/source code compile
	//for equal operators from (1) different hop dags and (2) repeated recompilation 
	//note: if PLAN_CACHE_SIZE is exceeded, we evict the least-recently-used plan (LRU policy)
	private static final PlanCache planCache = new PlanCache(PLAN_CACHE_SIZE);
	
	private static ProgramRewriter rewriteCSE = new ProgramRewriter(
			new RewriteCommonSubexpressionElimination(true),
			new RewriteRemoveUnnecessaryCasts());
	
	public static void generateCode(DMLProgram dmlprog) {
		// for each namespace, handle function statement blocks
		for (String namespaceKey : dmlprog.getNamespaces().keySet()) {
			for (String fname : dmlprog.getFunctionStatementBlocks(namespaceKey).keySet()) {
				FunctionStatementBlock fsblock = dmlprog.getFunctionStatementBlock(namespaceKey,fname);
				generateCodeFromStatementBlock(fsblock);
			}
		}
		
		// handle regular statement blocks in "main" method
		for (int i = 0; i < dmlprog.getNumStatementBlocks(); i++) {
			StatementBlock current = dmlprog.getStatementBlock(i);
			generateCodeFromStatementBlock(current);
		}
	}
	
	public static void generateCode(Program rtprog) {
		// handle all function program blocks
		for( FunctionProgramBlock pb : rtprog.getFunctionProgramBlocks().values() )
			generateCodeFromProgramBlock(pb);
		
		// handle regular program blocks in "main" method
		for( ProgramBlock pb : rtprog.getProgramBlocks() )
			generateCodeFromProgramBlock(pb);
	}
	
	public static void generateCodeFromStatementBlock(StatementBlock current) {
		if (current instanceof FunctionStatementBlock)
		{
			FunctionStatementBlock fsb = (FunctionStatementBlock)current;
			FunctionStatement fstmt = (FunctionStatement)fsb.getStatement(0);
			for (StatementBlock sb : fstmt.getBody())
				generateCodeFromStatementBlock(sb);
		}
		else if (current instanceof WhileStatementBlock)
		{
			WhileStatementBlock wsb = (WhileStatementBlock) current;
			WhileStatement wstmt = (WhileStatement)wsb.getStatement(0);
			wsb.setPredicateHops(optimize(wsb.getPredicateHops(), false));
			for (StatementBlock sb : wstmt.getBody())
				generateCodeFromStatementBlock(sb);
		}
		else if (current instanceof IfStatementBlock)
		{
			IfStatementBlock isb = (IfStatementBlock) current;
			IfStatement istmt = (IfStatement)isb.getStatement(0);
			isb.setPredicateHops(optimize(isb.getPredicateHops(), false));
			for (StatementBlock sb : istmt.getIfBody())
				generateCodeFromStatementBlock(sb);
			for (StatementBlock sb : istmt.getElseBody())
				generateCodeFromStatementBlock(sb);
		}
		else if (current instanceof ForStatementBlock) //incl parfor
		{
			ForStatementBlock fsb = (ForStatementBlock) current;
			ForStatement fstmt = (ForStatement)fsb.getStatement(0);
			fsb.setFromHops(optimize(fsb.getFromHops(), false));
			fsb.setToHops(optimize(fsb.getToHops(), false));
			fsb.setIncrementHops(optimize(fsb.getIncrementHops(), false));
			for (StatementBlock sb : fstmt.getBody())
				generateCodeFromStatementBlock(sb);
		}
		else //generic (last-level)
		{
			current.setHops( generateCodeFromHopDAGs(current.getHops()) );
			current.updateRecompilationFlag();
		}
	}
	
	public static void generateCodeFromProgramBlock(ProgramBlock current)
	{
		if (current instanceof FunctionProgramBlock) {
			FunctionProgramBlock fsb = (FunctionProgramBlock)current;
			for (ProgramBlock pb : fsb.getChildBlocks())
				generateCodeFromProgramBlock(pb);
		}
		else if (current instanceof WhileProgramBlock) {
			WhileProgramBlock wpb = (WhileProgramBlock) current;
			WhileStatementBlock wsb = (WhileStatementBlock)wpb.getStatementBlock();
			
			if( wsb!=null && wsb.getPredicateHops()!=null )
				wpb.setPredicate(generateCodeFromHopDAGsToInst(wsb.getPredicateHops()));
			for (ProgramBlock sb : wpb.getChildBlocks())
				generateCodeFromProgramBlock(sb);
		}
		else if (current instanceof IfProgramBlock) {
			IfProgramBlock ipb = (IfProgramBlock) current;
			IfStatementBlock isb = (IfStatementBlock) ipb.getStatementBlock();
			if( isb!=null && isb.getPredicateHops()!=null )
				ipb.setPredicate(generateCodeFromHopDAGsToInst(isb.getPredicateHops()));
			for (ProgramBlock pb : ipb.getChildBlocksIfBody())
				generateCodeFromProgramBlock(pb);
			for (ProgramBlock pb : ipb.getChildBlocksElseBody())
				generateCodeFromProgramBlock(pb);
		}
		else if (current instanceof ForProgramBlock) { //incl parfor
			ForProgramBlock fpb = (ForProgramBlock) current;
			ForStatementBlock fsb = (ForStatementBlock) fpb.getStatementBlock();
			if( fsb!=null && fsb.getFromHops()!=null )
				fpb.setFromInstructions(generateCodeFromHopDAGsToInst(fsb.getFromHops()));
			if( fsb!=null && fsb.getToHops()!=null )
				fpb.setToInstructions(generateCodeFromHopDAGsToInst(fsb.getToHops()));
			if( fsb!=null && fsb.getIncrementHops()!=null )
				fpb.setIncrementInstructions(generateCodeFromHopDAGsToInst(fsb.getIncrementHops()));
			for (ProgramBlock pb : fpb.getChildBlocks())
				generateCodeFromProgramBlock(pb);
		}
		else if( current instanceof BasicProgramBlock ) {
			BasicProgramBlock bpb = (BasicProgramBlock) current;
			StatementBlock sb = current.getStatementBlock();
			bpb.setInstructions( generateCodeFromHopDAGsToInst(sb, sb.getHops()) );
		}
	}

	public static ArrayList<Hop> generateCodeFromHopDAGs(ArrayList<Hop> roots) {
		if( roots == null )
			return roots;

		ArrayList<Hop> optimized = SpoofCompiler.optimize(roots, false);
		Hop.resetVisitStatus(roots);
		Hop.resetVisitStatus(optimized);
		
		return optimized;
	}
	
	public static ArrayList<Instruction> generateCodeFromHopDAGsToInst(StatementBlock sb, ArrayList<Hop> roots) {
		//create copy of hop dag, call codegen, and generate instructions
		return Recompiler.recompileHopsDag(sb, roots, 
			new LocalVariableMap(), new RecompileStatus(true), false, false, 0);
	}
	
	public static ArrayList<Instruction> generateCodeFromHopDAGsToInst(Hop root) {
		//create copy of hop dag, call codegen, and generate instructions
		return Recompiler.recompileHopsDag(root, 
			new LocalVariableMap(), new RecompileStatus(true), false, false, 0);
	}
	
	
	/**
	 * Main interface of sum-product optimizer, predicate dag.
	 * 
	 * @param root dag root node
	 * @param recompile true if invoked during dynamic recompilation
	 * @return dag root node of modified dag
	 */
	public static Hop optimize( Hop root, boolean recompile ) {
		if( root == null )
			return root;
		return optimize(new ArrayList<>(
			Collections.singleton(root)), recompile).get(0);
	}
	
	/**
	 * Main interface of sum-product optimizer, statement block dag.
	 * 
	 * @param roots dag root nodes
	 * @param recompile true if invoked during dynamic recompilation
	 * @return dag root nodes of modified dag 
	 */
	public static ArrayList<Hop> optimize(ArrayList<Hop> roots, boolean recompile) 
	{
		if( roots == null || roots.isEmpty() )
			return roots;
	
		long t0 = DMLScript.STATISTICS ? System.nanoTime() : 0;
		ArrayList<Hop> ret = roots;
		
		try
		{
			//context-sensitive literal replacement (only integers during recompile)
			boolean compileLiterals = (PLAN_CACHE_POLICY==PlanCachePolicy.CONSTANT) || !recompile;
			
			//candidate exploration of valid partial fusion plans
			CPlanMemoTable memo = new CPlanMemoTable();
			for( Hop hop : roots )
				rExploreCPlans(hop, memo, compileLiterals);
			
			//candidate selection of optimal fusion plan
			memo.pruneSuboptimal(roots);
			
			//construct actual cplan representations
			//note: we do not use the hop visit status due to jumps over fused operators which would
			//corrupt subsequent resets, leaving partial hops dags in visited status
			HashMap<Long, Pair<Hop[],CNodeTpl>> cplans = new LinkedHashMap<>();
			HashSet<Long> visited = new HashSet<>();
			for( Hop hop : roots )
				rConstructCPlans(hop, memo, cplans, compileLiterals, visited);
			
			//cleanup codegen plans (remove unnecessary inputs, fix hop-cnodedata mapping,
			//remove empty templates with single cnodedata input, remove spurious lookups,
			//perform common subexpression elimination)
			cplans = cleanupCPlans(memo, cplans);
			
			//explain before modification
			if( LOG.isTraceEnabled() && !cplans.isEmpty() ) { //existing cplans
				LOG.trace("Codegen EXPLAIN (before optimize): \n"+Explain.explainHops(roots));
			}
			
			//source code generation for all cplans
			HashMap<Long, Pair<Hop[],Class<?>>> clas = new HashMap<>();
			for( Entry<Long, Pair<Hop[],CNodeTpl>> cplan : cplans.entrySet() ) 
			{
				Pair<Hop[],CNodeTpl> tmp = cplan.getValue();
				Class<?> cla = planCache.getPlan(tmp.getValue());
				
				if( cla == null ) {
					//generate java source code
					String src = tmp.getValue().codegen(false);
					
					//explain debug output cplans or generated source code
					if( LOG.isTraceEnabled() || DMLScript.EXPLAIN.isHopsType(recompile) ) {
						LOG.info("Codegen EXPLAIN (generated cplan for HopID: " + cplan.getKey() + 
							", line "+tmp.getValue().getBeginLine() + ", hash="+tmp.getValue().hashCode()+"):");
						LOG.info(tmp.getValue().getClassname()
							+ Explain.explainCPlan(cplan.getValue().getValue()));
					}
					if( LOG.isTraceEnabled() || DMLScript.EXPLAIN.isRuntimeType(recompile) ) {
						LOG.info("Codegen EXPLAIN (generated code for HopID: " + cplan.getKey() + 
							", line "+tmp.getValue().getBeginLine() + ", hash="+tmp.getValue().hashCode()+"):");
						LOG.info(src);
					}
					
					//compile generated java source code
					cla = CodegenUtils.compileClass("codegen."+
							tmp.getValue().getClassname(), src);
					
					//maintain plan cache
					if( PLAN_CACHE_POLICY!=PlanCachePolicy.NONE )
						planCache.putPlan(tmp.getValue(), cla);
				}
				else if( DMLScript.STATISTICS ) {
					Statistics.incrementCodegenOpCacheHits();
				}
				
				//make class available and maintain hits
				if(cla != null)
					clas.put(cplan.getKey(), new Pair<Hop[],Class<?>>(tmp.getKey(),cla));
				if( DMLScript.STATISTICS )
					Statistics.incrementCodegenOpCacheTotal();
			}
			
			//create modified hop dag (operator replacement and CSE)
			if( !cplans.isEmpty() ) 
			{
				//generate final hop dag
				ret = constructModifiedHopDag(roots, cplans, clas);
				
				//run common subexpression elimination and other rewrites
				ret = rewriteCSE.rewriteHopDAG(ret, new ProgramRewriteStatus());
				
				//explain after modification
				if( LOG.isTraceEnabled() ) {
					LOG.trace("Codegen EXPLAIN (after optimize): \n"+Explain.explainHops(roots));
				}
			}
		}
		catch( Exception ex ) {
			LOG.error("Codegen failed to optimize the following HOP DAG: \n" + 
				Explain.explainHops(roots));
			throw new DMLRuntimeException(ex);
		}
		
		if( DMLScript.STATISTICS ) {
			Statistics.incrementCodegenDAGCompile();
			Statistics.incrementCodegenCompileTime(System.nanoTime()-t0);
		}
		
		Hop.resetVisitStatus(roots);
			
		return ret;
	}

	public static void cleanupCodeGenerator() {
		if( PLAN_CACHE_POLICY != PlanCachePolicy.NONE ) {
			CodegenUtils.clearClassCache(); //class cache
			planCache.clear(); //plan cache
		}
	}
	
	/**
	 * Factory method for alternative plan selection policies.
	 * 
	 * @return plan selector
	 */
	public static PlanSelection createPlanSelector() {
		switch( PLAN_SEL_POLICY ) {
			case FUSE_ALL: 
				return new PlanSelectionFuseAll();
			case FUSE_NO_REDUNDANCY: 
				return new PlanSelectionFuseNoRedundancy();
			case FUSE_COST_BASED:
				return new PlanSelectionFuseCostBased();
			case FUSE_COST_BASED_V2:
				return new PlanSelectionFuseCostBasedV2();
			default:	
				throw new RuntimeException("Unsupported "
					+ "plan selector: "+PLAN_SEL_POLICY);
		}
	}
	
	public static void setConfiguredPlanSelector() {
		DMLConfig conf = ConfigurationManager.getDMLConfig();
		String optimizer = conf.getTextValue(DMLConfig.CODEGEN_OPTIMIZER);
		PlanSelector type = PlanSelector.valueOf(optimizer.toUpperCase());
		PLAN_SEL_POLICY = type;
	}
	
	public static void setExecTypeSpecificJavaCompiler() {
		DMLConfig conf = ConfigurationManager.getDMLConfig();
		String compiler = conf.getTextValue(DMLConfig.CODEGEN_COMPILER);
		CompilerType type = CompilerType.valueOf(compiler.toUpperCase());
		JAVA_COMPILER = (type != CompilerType.AUTO) ? type :
			OptimizerUtils.isSparkExecutionMode() ? 
			CompilerType.JANINO : CompilerType.JAVAC;
	}
	
	////////////////////
	// Codegen plan construction
	
	private static void rExploreCPlans(Hop hop, CPlanMemoTable memo, boolean compileLiterals) {
		//top-down memoization of processed dag nodes
		if( memo.contains(hop.getHopID()) || memo.containsHop(hop) )
			return;
		
		//recursive candidate exploration
		for( Hop c : hop.getInput() )
			rExploreCPlans(c, memo, compileLiterals);
		
		//open initial operator plans, if possible
		for( TemplateBase tpl : TemplateUtils.TEMPLATES )
			if( tpl.open(hop) )
				memo.addAll(hop, enumPlans(hop, null, tpl, memo));
		
		//fuse and merge operator plans
		for( Hop c : hop.getInput() )
			for( TemplateBase tpl : memo.getDistinctTemplates(c.getHopID()) )
				if( tpl.fuse(hop, c) )
					memo.addAll(hop, enumPlans(hop, c, tpl, memo));
		
		//close operator plans, if required
		if( memo.contains(hop.getHopID()) ) {
			Iterator<MemoTableEntry> iter = memo.get(hop.getHopID()).iterator();
			while( iter.hasNext() ) {
				MemoTableEntry me = iter.next();
				TemplateBase tpl = TemplateUtils.createTemplate(me.type);
				CloseType ccode = tpl.close(hop);
				if( ccode == CloseType.CLOSED_INVALID )
					iter.remove();
				me.ctype = ccode;
			}
		}
		
		//prune subsumed / redundant plans
		if( PRUNE_REDUNDANT_PLANS ) {
			memo.pruneRedundant(hop.getHopID(),
				PLAN_SEL_POLICY.isHeuristic(), null);
		}
		
		//mark visited even if no plans found (e.g., unsupported ops)
		memo.addHop(hop);
	}
	
	private static MemoTableEntrySet enumPlans(Hop hop, Hop c, TemplateBase tpl, CPlanMemoTable memo) {
		MemoTableEntrySet P = new MemoTableEntrySet(hop, c, tpl);
		for(int k=0; k<hop.getInput().size(); k++) {
			Hop input2 = hop.getInput().get(k);
			if( input2 != c && tpl.merge(hop, input2) 
				&& memo.contains(input2.getHopID(), true, tpl.getType(), TemplateType.CELL))
				P.crossProduct(k, -1L, input2.getHopID());
		}
		return P;
	}
	
	private static void rConstructCPlans(Hop hop, CPlanMemoTable memo, HashMap<Long, Pair<Hop[],CNodeTpl>> cplans, boolean compileLiterals, HashSet<Long> visited) {
		//top-down memoization of processed dag nodes
		if( hop == null || visited.contains(hop.getHopID()) )
			return;
		
		//generate cplan for existing memo table entry
		if( memo.containsTopLevel(hop.getHopID()) ) {
			cplans.put(hop.getHopID(), TemplateUtils
					.createTemplate(memo.getBest(hop.getHopID()).type)
					.constructCplan(hop, memo, compileLiterals));
			if (DMLScript.STATISTICS)
				Statistics.incrementCodegenCPlanCompile(1);
		}
		
		//process children recursively, but skip compiled operator
		if( cplans.containsKey(hop.getHopID()) ) {
			for( Hop c : cplans.get(hop.getHopID()).getKey() )
				rConstructCPlans(c, memo, cplans, compileLiterals, visited);
		}
		else {
			for( Hop c : hop.getInput() )
				rConstructCPlans(c, memo, cplans, compileLiterals, visited);
		}
		
		visited.add(hop.getHopID());
	}
	
	////////////////////
	// Codegen hop dag construction

	private static ArrayList<Hop> constructModifiedHopDag(ArrayList<Hop> orig, 
			HashMap<Long, Pair<Hop[],CNodeTpl>> cplans, HashMap<Long, Pair<Hop[],Class<?>>> cla)
	{
		HashSet<Long> memo = new HashSet<>();
		for( int i=0; i<orig.size(); i++ ) {
			Hop hop = orig.get(i); //w/o iterator because modified
			rConstructModifiedHopDag(hop, cplans, cla, memo);
		}
		return orig;
	}
	
	private static void rConstructModifiedHopDag(Hop hop,  HashMap<Long, Pair<Hop[],CNodeTpl>> cplans,
			HashMap<Long, Pair<Hop[],Class<?>>> clas, HashSet<Long> memo)
	{
		if( memo.contains(hop.getHopID()) )
			return; //already processed
		
		Hop hnew = hop;
		if( clas.containsKey(hop.getHopID()) ) 
		{
			//replace sub-dag with generated operator
			Pair<Hop[], Class<?>> tmpCla = clas.get(hop.getHopID());
			CNodeTpl tmpCNode = cplans.get(hop.getHopID()).getValue();
			hnew = new SpoofFusedOp(hop.getName(), hop.getDataType(), hop.getValueType(), 
					tmpCla.getValue(), false, tmpCNode.getOutputDimType());
			Hop[] inHops = tmpCla.getKey();
			for( int i=0; i<inHops.length; i++ ) {
				if( tmpCNode instanceof CNodeOuterProduct 
					&& inHops[i].getHopID()==((CNodeData)tmpCNode.getInput().get(2)).getHopID()
					&& !TemplateUtils.hasTransposeParentUnderOuterProduct(inHops[i]) ) {
					hnew.addInput(HopRewriteUtils.createTranspose(inHops[i]));
				}
				else
					hnew.addInput(inHops[i]); //add inputs
			}
			
			//modify output parameters 
			HopRewriteUtils.setOutputParameters(hnew, hop.getDim1(), hop.getDim2(), 
					hop.getRowsInBlock(), hop.getColsInBlock(), hop.getNnz());
			if(tmpCNode instanceof CNodeOuterProduct && ((CNodeOuterProduct)tmpCNode).isTransposeOutput() )
				hnew = HopRewriteUtils.createTranspose(hnew);
			else if( tmpCNode instanceof CNodeMultiAgg ) {
				ArrayList<Hop> roots = ((CNodeMultiAgg)tmpCNode).getRootNodes();
				hnew.setDataType(DataType.MATRIX);
				HopRewriteUtils.setOutputParameters(hnew, 1, roots.size(), 
					inHops[0].getRowsInBlock(), inHops[0].getColsInBlock(), -1);
				//inject artificial right indexing operations for all parents of all nodes
				for( int i=0; i<roots.size(); i++ ) {
					Hop hnewi = (roots.get(i) instanceof AggUnaryOp) ? 
						HopRewriteUtils.createScalarIndexing(hnew, 1, i+1) :
						HopRewriteUtils.createIndexingOp(hnew, 1, i+1);
					HopRewriteUtils.rewireAllParentChildReferences(roots.get(i), hnewi);
				}
			}
			else if( tmpCNode instanceof CNodeCell && ((CNodeCell)tmpCNode).requiredCastDtm() ) {
				HopRewriteUtils.setOutputParametersForScalar(hnew);
				hnew = HopRewriteUtils.createUnary(hnew, OpOp1.CAST_AS_MATRIX);
			}
			else if( tmpCNode instanceof CNodeRow && (((CNodeRow)tmpCNode).getRowType()==RowType.NO_AGG_CONST
				|| ((CNodeRow)tmpCNode).getRowType()==RowType.COL_AGG_CONST) )
				((SpoofFusedOp)hnew).setConstDim2(((CNodeRow)tmpCNode).getConstDim2());
			
			if( !(tmpCNode instanceof CNodeMultiAgg) )
				HopRewriteUtils.rewireAllParentChildReferences(hop, hnew);
			memo.add(hnew.getHopID());
		}
		
		//process hops recursively (parent-child links modified)
		for( int i=0; i<hnew.getInput().size(); i++ ) {
			Hop c = hnew.getInput().get(i);
			rConstructModifiedHopDag(c, cplans, clas, memo);
		}
		memo.add(hnew.getHopID());
	}
	
	/**
	 * Cleanup generated cplans in order to remove unnecessary inputs created
	 * during incremental construction. This is important as it avoids unnecessary 
	 * redundant computation. 
	 * 
	 * @param memo memoization table
	 * @param cplans set of cplans
	 */
	private static HashMap<Long, Pair<Hop[],CNodeTpl>> cleanupCPlans(CPlanMemoTable memo, HashMap<Long, Pair<Hop[],CNodeTpl>> cplans) 
	{
		HashMap<Long, Pair<Hop[],CNodeTpl>> cplans2 = new HashMap<>();
		CPlanOpRewriter rewriter = new CPlanOpRewriter();
		CPlanCSERewriter cse = new CPlanCSERewriter();
		
		for( Entry<Long, Pair<Hop[],CNodeTpl>> e : cplans.entrySet() ) {
			CNodeTpl tpl = e.getValue().getValue();
			Hop[] inHops = e.getValue().getKey();
			
			//remove invalid plans with null, empty, or all scalar inputs 
			if( inHops == null || inHops.length == 0
				|| Arrays.stream(inHops).anyMatch(h -> (h==null))
				|| Arrays.stream(inHops).allMatch(h -> h.isScalar()))
				continue;
			
			//perform simplifications and cse rewrites
			tpl = rewriter.simplifyCPlan(tpl);
			tpl = cse.eliminateCommonSubexpressions(tpl);
			
			//update input hops (order-preserving)
			HashSet<Long> inputHopIDs = tpl.getInputHopIDs(false);
			inHops = Arrays.stream(inHops)
				.filter(p -> p != null && inputHopIDs.contains(p.getHopID()))
				.toArray(Hop[]::new);
			cplans2.put(e.getKey(), new Pair<>(inHops, tpl));
			
			//remove invalid plans with column indexing on main input
			if( tpl instanceof CNodeCell || tpl instanceof CNodeRow ) {
				CNodeData in1 = (CNodeData)tpl.getInput().get(0);
				boolean inclRC1 = !(tpl instanceof CNodeRow);
				if( rHasLookupRC1(tpl.getOutput(), in1, inclRC1) || isLookupRC1(tpl.getOutput(), in1, inclRC1) ) {
					cplans2.remove(e.getKey());
					if( LOG.isTraceEnabled() )
						LOG.trace("Removed cplan due to invalid rc1 indexing on main input.");
				}
			}
			else if( tpl instanceof CNodeMultiAgg ) {
				CNodeData in1 = (CNodeData)tpl.getInput().get(0);
				for( CNode output : ((CNodeMultiAgg)tpl).getOutputs() )
					if( rHasLookupRC1(output, in1, true) || isLookupRC1(output, in1, true) ) {
						cplans2.remove(e.getKey());
						if( LOG.isTraceEnabled() )
							LOG.trace("Removed cplan due to invalid rc1 indexing on main input.");
					}
			}
			
			//remove invalid lookups on main input (all templates)
			CNodeData in1 = (CNodeData)tpl.getInput().get(0);
			if( tpl instanceof CNodeMultiAgg )
				rFindAndRemoveLookupMultiAgg((CNodeMultiAgg)tpl, in1);
			else
				rFindAndRemoveLookup(tpl.getOutput(), in1, !(tpl instanceof CNodeRow));
			
			//remove invalid row templates (e.g., unsatisfied blocksize constraint)
			if( tpl instanceof CNodeRow ) {
				//check for invalid row cplan over column vector
				if( ((CNodeRow)tpl).getRowType()==RowType.NO_AGG && tpl.getOutput().getDataType().isScalar() ) {
					cplans2.remove(e.getKey());
					if( LOG.isTraceEnabled() )
						LOG.trace("Removed invalid row cplan w/o agg on column vector.");
				}
				else if( OptimizerUtils.isSparkExecutionMode() ) {
					Hop hop = memo.getHopRefs().get(e.getKey());
					boolean isSpark = DMLScript.getGlobalExecMode() == ExecMode.SPARK
						|| OptimizerUtils.getTotalMemEstimate(inHops, hop, true)
							> OptimizerUtils.getLocalMemBudget();
					boolean invalidNcol = hop.getDataType().isMatrix() && (HopRewriteUtils.isTransposeOperation(hop) ?
						hop.getDim1() > hop.getRowsInBlock() : hop.getDim2() > hop.getColsInBlock());
					for( Hop in : inHops )
						invalidNcol |= (in.getDataType().isMatrix() 
							&& in.getDim2() > in.getColsInBlock());
					if( isSpark && invalidNcol ) {
						cplans2.remove(e.getKey());
						if( LOG.isTraceEnabled() )
							LOG.trace("Removed invalid row cplan w/ ncol>ncolpb.");
					}
				}
			}
			
			//remove cplan w/ single op and w/o agg
			if( (tpl instanceof CNodeCell && ((CNodeCell)tpl).getCellType()==CellType.NO_AGG
					&& TemplateUtils.hasSingleOperation(tpl) )
				|| (tpl instanceof CNodeRow && (((CNodeRow)tpl).getRowType()==RowType.NO_AGG
					|| ((CNodeRow)tpl).getRowType()==RowType.NO_AGG_B1
					|| ((CNodeRow)tpl).getRowType()==RowType.ROW_AGG )
					&& TemplateUtils.hasSingleOperation(tpl))
				|| TemplateUtils.hasNoOperation(tpl) ) 
			{
				cplans2.remove(e.getKey());
				if( LOG.isTraceEnabled() )
					LOG.trace("Removed cplan with single operation.");
			}
			
			//remove cplan if empty
			if( tpl.getOutput() instanceof CNodeData ) {
				cplans2.remove(e.getKey());
				if( LOG.isTraceEnabled() )
					LOG.trace("Removed empty cplan.");
			}
			
			//rename inputs (for codegen and plan caching)
			tpl.renameInputs();
		}
		
		return cplans2;
	}
	
	private static void rFindAndRemoveLookupMultiAgg(CNodeMultiAgg node, CNodeData mainInput) {
		//process all outputs individually
		for( CNode output : node.getOutputs() )
			rFindAndRemoveLookup(output, mainInput, true);
		
		//handle special case, of lookup being itself the output node
		for( int i=0; i < node.getOutputs().size(); i++) {
			CNode tmp = node.getOutputs().get(i);
			if( TemplateUtils.isLookup(tmp, true) && tmp.getInput().get(0) instanceof CNodeData
				&& ((CNodeData)tmp.getInput().get(0)).getHopID()==mainInput.getHopID() )
				node.getOutputs().set(i, tmp.getInput().get(0));
		}
	}
	
	private static void rFindAndRemoveLookup(CNode node, CNodeData mainInput, boolean includeRC1) {
		for( int i=0; i<node.getInput().size(); i++ ) {
			CNode tmp = node.getInput().get(i);
			if( TemplateUtils.isLookup(tmp, includeRC1) && tmp.getInput().get(0) instanceof CNodeData
				&& ((CNodeData)tmp.getInput().get(0)).getHopID()==mainInput.getHopID() )
			{
				node.getInput().set(i, tmp.getInput().get(0));
			}
			else
				rFindAndRemoveLookup(tmp, mainInput, includeRC1);
		}
	}
	
	private static boolean rHasLookupRC1(CNode node, CNodeData mainInput, boolean includeRC1) {
		boolean ret = false;
		for( int i=0; i<node.getInput().size() && !ret; i++ ) {
			CNode tmp = node.getInput().get(i);
			if( isLookupRC1(tmp, mainInput, includeRC1) )
				ret = true;
			else
				ret |= rHasLookupRC1(tmp, mainInput, includeRC1);
		}
		return ret;
	}
	
	private static boolean isLookupRC1(CNode node, CNodeData mainInput, boolean includeRC1) {
		return (node instanceof CNodeTernary && ((((CNodeTernary)node).getType()==TernaryType.LOOKUP_RC1 && includeRC1)
				|| ((CNodeTernary)node).getType()==TernaryType.LOOKUP_RVECT1 )
				&& node.getInput().get(0) instanceof CNodeData
				&& ((CNodeData)node.getInput().get(0)).getHopID() == mainInput.getHopID());
	}
	
	/**
	 * This plan cache maps CPlans to compiled and loaded classes in order
	 * to reduce javac and JIT compilation overhead. It uses a simple LRU 
	 * eviction policy if the maximum number of entries is exceeded. In case 
	 * of evictions, this cache also triggers the eviction of corresponding 
	 * class cache entries (1:N). 
	 * <p>
	 * Note: The JVM is free to garbage collect and unload classes that are no
	 * longer referenced.
	 * 
	 */
	private static class PlanCache {
		private final LinkedHashMap<CNode, Class<?>> _plans;
		private final int _maxSize;
		
		public PlanCache(int maxSize) {
			 _plans = new LinkedHashMap<>();
			 _maxSize = maxSize;
		}
		
		public synchronized Class<?> getPlan(CNode key) {
			//constant time get and maintain usage order
			Class<?> value = _plans.remove(key);
			if( value != null ) 
				_plans.put(key, value); 
			return value;
		}
		
		public synchronized void putPlan(CNode key, Class<?> value) {
			if( _plans.size() >= _maxSize ) {
				//remove least recently used (i.e., first) entry
				Iterator<Entry<CNode, Class<?>>> iter = _plans.entrySet().iterator();
				Class<?> rmCla = iter.next().getValue();
				CodegenUtils.clearClassCache(rmCla); //class cache
				iter.remove(); //plan cache
			}
			_plans.put(key, value);
		}
		
		public synchronized void clear() {
			_plans.clear();
		}
	}
}
