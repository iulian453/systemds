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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.tugraz.sysds.conf.DMLConfig;
import org.tugraz.sysds.hops.OptimizerUtils;
import org.tugraz.sysds.runtime.controlprogram.parfor.stat.InfrastructureAnalyzer;
import org.tugraz.sysds.runtime.matrix.data.MatrixBlock;
import org.tugraz.sysds.runtime.matrix.data.OutputInfo;
import org.tugraz.sysds.runtime.util.CommonThreadPool;
import org.tugraz.sysds.runtime.util.HDFSTool;

public class WriterTextLIBSVMParallel extends WriterTextLIBSVM
{
	public WriterTextLIBSVMParallel() {
		super();
	}

	@Override
	protected void writeLIBSVMMatrixToHDFS(Path path, JobConf job, FileSystem fs, MatrixBlock src)
		throws IOException 
	{
		//estimate output size and number of output blocks (min 1)
		int numPartFiles = (int)(OptimizerUtils.estimateSizeTextOutput(src.getNumRows(), src.getNumColumns(),
				src.getNonZeros(), OutputInfo.LIBSVMOutputInfo)  / InfrastructureAnalyzer.getHDFSBlockSize());
		numPartFiles = Math.max(numPartFiles, 1);
		
		//determine degree of parallelism
		int numThreads = OptimizerUtils.getParallelTextWriteParallelism();
		numThreads = Math.min(numThreads, numPartFiles);
	
		//fall back to sequential write if dop is 1 (e.g., <128MB) in order to create single file
		if( numThreads <= 1 ) {
			super.writeLIBSVMMatrixToHDFS(path, job, fs, src);
			return;
		}
		
		//create directory for concurrent tasks
		HDFSTool.createDirIfNotExistOnHDFS(path, DMLConfig.DEFAULT_SHARED_DIR_PERMISSION);
		
		//create and execute tasks
		try 
		{
			ExecutorService pool = CommonThreadPool.get(numThreads);
			ArrayList<WriteLIBSVMTask> tasks = new ArrayList<>();
			int rlen = src.getNumRows();
			int blklen = (int)Math.ceil((double)rlen / numThreads);
			for(int i=0; i<numThreads & i*blklen<rlen; i++) {
				Path newPath = new Path(path, IOUtilFunctions.getPartFileName(i));
				tasks.add(new WriteLIBSVMTask(newPath, job, fs, src, i*blklen, (int)Math.min((i+1)*blklen, rlen)));
			}

			//wait until all tasks have been executed
			List<Future<Object>> rt = pool.invokeAll(tasks);
			pool.shutdown();
			
			//check for exceptions 
			for( Future<Object> task : rt )
				task.get();
			
			// delete crc files if written to local file system
			if (fs instanceof LocalFileSystem) {
				for(int i=0; i<numThreads & i*blklen<rlen; i++) 
					IOUtilFunctions.deleteCrcFilesFromLocalFileSystem(fs,
						new Path(path, IOUtilFunctions.getPartFileName(i)));
			}
		} 
		catch (Exception e) {
			throw new IOException("Failed parallel write of libsvm output.", e);
		}
	}

	private class WriteLIBSVMTask implements Callable<Object> 
	{
		private final JobConf _job;
		private final FileSystem _fs;
		private final MatrixBlock _src;
		private final Path _path;
		private final int _rl, _ru;
		
		public WriteLIBSVMTask(Path path, JobConf job, FileSystem fs, MatrixBlock src, int rl, int ru) {
			_path = path;
			_job = job;
			_fs = fs;
			_src = src;
			_rl = rl;
			_ru = ru;
		}

		@Override
		public Object call() throws Exception {
			writeLIBSVMMatrixToFile(_path, _job, _fs, _src, _rl, _ru);
			return null;
		}
	}
}
