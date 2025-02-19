/*
 * Copyright 2019 Graz University of Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tugraz.sysds.runtime.io;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.tugraz.sysds.conf.ConfigurationManager;
import org.tugraz.sysds.runtime.DMLRuntimeException;
import org.tugraz.sysds.runtime.data.SparseBlock;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.util.HDFSTool;

public class WriterTextLIBSVM extends MatrixWriter
{
	public WriterTextLIBSVM() {
	
	}

	@Override
	public final void writeMatrixToHDFS(MatrixBlock src, String fname, long rlen, long clen, int brlen, int bclen, long nnz, boolean diag) 
		throws IOException, DMLRuntimeException 
	{
		//validity check matrix dimensions
		if( src.getNumRows() != rlen || src.getNumColumns() != clen )
			throw new IOException("Matrix dimensions mismatch with metadata: "+src.getNumRows()+"x"+src.getNumColumns()+" vs "+rlen+"x"+clen+".");
		if( rlen == 0 || clen == 0 )
			throw new IOException("Write of matrices with zero rows or columns not supported ("+rlen+"x"+clen+").");
		
		//prepare file access
		JobConf job = new JobConf(ConfigurationManager.getCachedJobConf());
		Path path = new Path( fname );
		FileSystem fs = IOUtilFunctions.getFileSystem(path, job);
		
		//if the file already exists on HDFS, remove it.
		HDFSTool.deleteFileIfExistOnHDFS( fname );
		
		//core write (sequential/parallel)
		writeLIBSVMMatrixToHDFS(path, job, fs, src);

		IOUtilFunctions.deleteCrcFilesFromLocalFileSystem(fs, path);
	}

	@Override
	public final void writeEmptyMatrixToHDFS(String fname, long rlen, long clen, int brlen, int bclen) 
		throws IOException, DMLRuntimeException 
	{
	
	}

	protected void writeLIBSVMMatrixToHDFS(Path path, JobConf job, FileSystem fs, MatrixBlock src) 
		throws IOException 
	{
		//sequential write libsvm file
		writeLIBSVMMatrixToFile(path, job, fs, src, 0, (int)src.getNumRows());
	}
	
	protected static void writeLIBSVMMatrixToFile( Path path, JobConf job, FileSystem fs, MatrixBlock src, int rl, int rlen )
		throws IOException
	{
		boolean sparse = src.isInSparseFormat();
		int clen = src.getNumColumns();
			
		//create buffered writer
		BufferedWriter br = new BufferedWriter(new OutputStreamWriter(fs.create(path,true)));
		
		try
		{
			StringBuilder sb = new StringBuilder();
			
			// Write data lines
			if( sparse ) //SPARSE
			{
				SparseBlock sblock = src.getSparseBlock();
				for(int i=rl; i < rlen; i++) {
					// append the class label as the 1st column
					double label = (sblock!=null) ? 
						sblock.get(i, clen-1) : 0;
					sb.append(label);
					sb.append(IOUtilFunctions.LIBSVM_DELIM);
					
					if( sblock!=null && i<sblock.numRows() && !sblock.isEmpty(i) ) {
						int pos = sblock.pos(i);
						int alen = sblock.size(i);
						int[] aix = sblock.indexes(i);
						double[] avals = sblock.values(i);
						// append sparse row
						for( int k=pos; k<pos+alen; k++ ) {
							if( aix[k] != clen-1 )
								appendIndexValLibsvm(sb, aix[k]+1, avals[k]);
							sb.append(IOUtilFunctions.LIBSVM_DELIM);
						}
					}
					// write the string row
					sb.append('\n');
					br.write( sb.toString() );
					sb.setLength(0); 
				}
			}
			else //DENSE
			{
				for( int i=rl; i<rlen; i++ ) {
					// append the class label as the 1st column
					double label = src.getValueDenseUnsafe(i, clen-1);
					sb.append(label);
					sb.append(IOUtilFunctions.LIBSVM_DELIM);
					
					// append dense row
					for( int j=0; j<clen-1; j++ ) {
						double val = src.getValueDenseUnsafe(i, j);
						if( val != 0 ) {
							appendIndexValLibsvm(sb, j, val);
							sb.append(IOUtilFunctions.LIBSVM_DELIM);
						}
					}
					// write the string row
					sb.append('\n');
					br.write( sb.toString() );
					sb.setLength(0);
				}
			}
		}
		finally {
			IOUtilFunctions.closeSilently(br);
		}
	}

	// Return string in libsvm format (<index#>:<value#>) 
	protected static void appendIndexValLibsvm(StringBuilder sb, int index, double value) {
		sb.append(index+1);  // convert 0 based matrix index to 1 base libsvm index
		sb.append(IOUtilFunctions.LIBSVM_INDEX_DELIM);
		sb.append(value);
	}
}
