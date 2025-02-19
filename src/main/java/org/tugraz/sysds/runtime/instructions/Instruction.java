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

package org.tugraz.sysds.runtime.instructions;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.tugraz.sysds.api.DMLScript;
import org.tugraz.sysds.lops.Lop;
import org.tugraz.sysds.parser.DataIdentifier;
import org.tugraz.sysds.runtime.controlprogram.context.ExecutionContext;
import org.tugraz.sysds.runtime.lineage.Lineage;


public abstract class Instruction 
{
	public enum IType { 
		CONTROL_PROGRAM,
		MAPREDUCE,
		MAPREDUCE_JOB,
		BREAKPOINT,
		SPARK,
		GPU
	}
	
	protected static final Log LOG = LogFactory.getLog(Instruction.class.getName());
	
	public static final String OPERAND_DELIM = Lop.OPERAND_DELIMITOR;
	public static final String DATATYPE_PREFIX = Lop.DATATYPE_PREFIX;
	public static final String VALUETYPE_PREFIX = Lop.VALUETYPE_PREFIX;
	public static final String LITERAL_PREFIX = Lop.LITERAL_PREFIX;
	public static final String INSTRUCTION_DELIM = Lop.INSTRUCTION_DELIMITOR;
	public static final String SP_INST_PREFIX = "sp_";
	public static final String GPU_INST_PREFIX = "gpu_";
	
	//basic instruction meta data
	protected String instString = null;
	protected String instOpcode = null;
	private String extendedOpcode = null;
	private long instID = -1;
	
	//originating script positions
	protected String filename = null;
	protected int beginLine = -1;
	protected int endLine = -1;  
	protected int beginCol = -1; 
	protected int endCol = -1;
	
	public String getFilename() {
		return filename;
	}

	public int getBeginLine() {
		return beginLine;
	}

	public int getEndLine() {
		return endLine;
	}

	public int getBeginColumn() {
		return beginCol;
	}

	public int getEndColumn() {
		return endCol;
	}
	
	public abstract IType getType();
	
	public void setLocation(String filename, int beginLine, int endLine, int beginCol, int endCol) {
		this.filename = filename;
		this.beginLine = beginLine;
		this.endLine = endLine;
		this.beginCol = beginCol;
		this.endCol = endCol;
	}
	
	public void setLocation(Lop lop) {
		if(lop != null) {
			this.filename = lop.getFilename();
			this.beginLine = lop._beginLine;
			this.endLine = lop._endLine;
			this.beginCol = lop._beginColumn;
			this.endCol = lop._endColumn;
		}
	}
	
	public void setLocation(DataIdentifier id) {
		if(id != null) {
			this.filename = id.getFilename();
			this.beginLine = id.getBeginLine();
			this.endLine = id.getEndLine();
			this.beginCol = id.getBeginColumn();
			this.endCol = id.getEndColumn();
		}
	}
	
	public void setLocation(Instruction oldInst) {
		if(oldInst != null) {
			this.filename = oldInst.filename;
			this.beginLine = oldInst.beginLine;
			this.endLine = oldInst.endLine;
			this.beginCol = oldInst.beginCol;
			this.endCol = oldInst.endCol;
		}
	}
	
	/**
	 * Getter for instruction line number
	 * @return lineNum Instruction approximate DML script line number
	 */
	public int getLineNum() {
		return beginLine;
	}

	/**
	 * Setter for instruction unique identifier 
	 * @param id Instruction unique identifier
	 */
	public void setInstID ( long id ) {
		instID = id;
	}
		
	/**
	 * Getter for instruction unique identifier
	 * @return instID Instruction unique identifier
	 */
	public long getInstID() {
		return instID;
	}

	public void printMe() {
		LOG.debug(instString);
	}
	
	@Override
	public String toString() {
		return instString;
	}
	
	public String getGraphString() {
		return null;
	}

	public String getOpcode() {
		return instOpcode;
	}
	
	public String getExtendedOpcode() {
		if(extendedOpcode != null)
			return extendedOpcode;
		if(DMLScript.FINEGRAINED_STATISTICS) {
			String scriptInfo;
			if(filename != null)
				scriptInfo = " [" + filename + " " + beginLine + ":" + beginCol + "-" + endLine + ":" + endCol + "]";
			else
				scriptInfo = " [" + beginLine + ":" + beginCol + "-" + endLine + ":" + endCol + "]";
			if( getType() == IType.SPARK )
				extendedOpcode = SP_INST_PREFIX + getOpcode() + scriptInfo;
			else if( getType() == IType.GPU )
				extendedOpcode = GPU_INST_PREFIX + getOpcode() + scriptInfo;
			else
				extendedOpcode = getOpcode() + scriptInfo;
		}
		else {
			// This ensures that there is no overhead if finegrained statistics is disabled
			if( getType() == IType.SPARK )
				extendedOpcode = SP_INST_PREFIX + getOpcode();
			else if( getType() == IType.GPU )
				extendedOpcode = GPU_INST_PREFIX + getOpcode();
			else
				extendedOpcode = getOpcode();
		}
		return extendedOpcode;
	}

	public boolean requiresLabelUpdate()
	{
		return instString.contains( Lop.VARIABLE_NAME_PLACEHOLDER );
	}	
	
	/**
	 * All instructions that have thread-specific filenames or names encoded in it
	 * should overwrite this method in order to update (1) the in-memory instruction
	 * and (2) the instruction string 
	 * 
	 * @param pattern ?
	 * @param replace ?
	 */
	public void updateInstructionThreadID(String pattern, String replace) {
		//do nothing
	}
	
	/**
	 * This method should be used for any setup before executing this instruction.
	 * Overwriting methods should first call the super method and subsequently do
	 * their custom setup.
	 * 
	 * @param ec execution context
	 * @return instruction
	 */
	public Instruction preprocessInstruction(ExecutionContext ec){
		// Lineage tracing
		if (DMLScript.LINEAGE)
			Lineage.trace(this, ec);
		//return instruction ifself
		return this;
	}
	
	/**
	 * This method should be used to execute the instruction. 
	 * 
	 * @param ec execution context
	 */
	public abstract void processInstruction(ExecutionContext ec);
	
	/**
	 * This method should be used for any tear down after executing this instruction.
	 * Overwriting methods should first do their custom tear down and subsequently 
	 * call the super method.
	 * 
	 * @param ec execution context
	 */
	public void postprocessInstruction(ExecutionContext ec) {
	}
}
