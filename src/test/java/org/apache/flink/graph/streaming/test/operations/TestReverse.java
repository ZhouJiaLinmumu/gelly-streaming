/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.graph.streaming.test.operations;

import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.graph.streaming.GraphStream;
import org.apache.flink.graph.streaming.test.GraphStreamTestUtils;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.util.StreamingProgramTestBase;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

public class TestReverse extends StreamingProgramTestBase {

	private String resultPath;
	private String expectedResult;

	@Rule
	public TemporaryFolder tempFolder = new TemporaryFolder();

	@Override
	protected void preSubmit() throws Exception {
		resultPath = tempFolder.newFile().toURI().toString();
	}

	@Override
	protected void postSubmit() throws Exception {
		compareResultsByLinesInMemory(expectedResult, resultPath);
	}

	@Override
	protected void testProgram() throws Exception {
		   	/*
		 * Test reverse() with the sample graph
	     */
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		GraphStream<Long, Long> graph =
				new GraphStream<>(GraphStreamTestUtils.getLongLongEdgeDataStream(env), env);

		graph.reverse().getEdges()
				.writeAsCsv(resultPath, FileSystem.WriteMode.OVERWRITE);

		env.execute();
		expectedResult = "2,1,12\n" +
				"3,1,13\n" +
				"3,2,23\n" +
				"4,3,34\n" +
				"5,3,35\n" +
				"5,4,45\n" +
				"1,5,51\n";
	}
}