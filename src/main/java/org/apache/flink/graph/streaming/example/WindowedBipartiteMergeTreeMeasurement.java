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

package org.apache.flink.graph.streaming.example;

import org.apache.flink.api.common.JobExecutionResult;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.streaming.example.utils.Candidate;
import org.apache.flink.graph.streaming.example.utils.SignedVertex;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.RichWindowMapFunction;
import org.apache.flink.streaming.api.windowing.helper.Count;
import org.apache.flink.types.NullValue;
import org.apache.flink.util.Collector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WindowedBipartiteMergeTreeMeasurement {

	public static long measure(int windowSize) throws Exception {
		StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment();
		env.setParallelism(4);

		// Source: http://grouplens.org/datasets/movielens/
		DataStream<Edge<Long, NullValue>> edges = env
				.readTextFile("movielens_tmp_sorted.txt")
				.map(new MapFunction<String, Edge<Long, NullValue>>() {
					@Override
					public Edge<Long, NullValue> map(String s) throws Exception {
						String[] args = s.split(",");
						long src = Long.parseLong(args[0]);
						long trg = Long.parseLong(args[1]) + 1000000;
						return new Edge<>(src, trg, NullValue.getInstance());
					}
				});

		edges.map(new InitCandidateMapper())
				.window(Count.of(windowSize))
				.mapWindow(new WindowedBipartitenessMapper())
				.flatten()

				// .groupBy(new MergeTreeKeySelector(0))
				// .window(Count.of(1000))
				// 	.mapWindow(new WindowedBipartitenessMapper())
				// .flatten()

				.groupBy(new MergeTreeKeySelector(1))
				.window(Count.of(100))
				.mapWindow(new WindowedBipartitenessMapper())
				.flatten();

		JobExecutionResult res = env.execute("Distributed Merge Tree Sandbox");
		return res.getNetRuntime();
	}

	private static final class WindowedBipartitenessMapper extends RichWindowMapFunction<Candidate, Candidate> {
		private Candidate candidate = null;
		private boolean failed = false;
		private int self;

		@Override
		public void mapWindow(Iterable<Candidate> iterator, Collector<Candidate> out) throws Exception {
			for (Candidate input : iterator) {
				candidate = map(input);
			}
			out.collect(candidate);
		}

		public Candidate map(Candidate input) throws Exception {
			self = getRuntimeContext().getIndexOfThisSubtask();

			// Propagate failure
			if (!input.getSuccess() || failed) {
				return fail();
			}

			// Store and forward the first candidate
			if (candidate == null) {
				candidate = new Candidate(self, true, input);
				return candidate;
			}

			// Compare each input component with each candidate component and merge accordingly
			for (Map.Entry<Long, Map<Long, SignedVertex>> inEntry : input.getMap().entrySet()) {

				List<Long> mergeWith = new ArrayList<>();

				for (Map.Entry<Long, Map<Long, SignedVertex>> selfEntry : candidate.getMap().entrySet()) {
					long selfKey = selfEntry.getKey();

					// If the two components are exactly the same, skip them
					if (inEntry.getValue().keySet().containsAll(selfEntry.getValue().keySet())
							&& selfEntry.getValue().keySet().containsAll(inEntry.getValue().keySet())) {
						continue;
					}

					// Find vertices of input component in the candidate component
					for (long inVertex : inEntry.getValue().keySet()) {
						if (selfEntry.getValue().containsKey(inVertex)) {
							if (!mergeWith.contains(selfKey)) {
								mergeWith.add(selfKey);
								break;
							}
						}
					}
				}

				if (mergeWith.isEmpty()) {
					// If the input component is disjoint from all components of the candidate,
					// simply add that component
					candidate.add(inEntry.getKey(), inEntry.getValue());
				} else {
					// Merge the input with the lowest id component in candidate
					Collections.sort(mergeWith);
					long firstKey = mergeWith.get(0);
					boolean success;

					success = merge(input, inEntry.getKey(), firstKey);
					if (!success) {
						return fail();
					}

					firstKey = Math.min(inEntry.getKey(), firstKey);

					// Merge other components of candidate into the lowest id component
					for (int i = 1; i < mergeWith.size(); ++i) {

						success = merge(candidate, mergeWith.get(i), firstKey);
						if (!success) {
							fail();
						}

						candidate.getMap().remove(mergeWith.get(i));
					}
				}
			}

			return candidate;
		}

		private boolean merge(Candidate input, long inputKey, long selfKey) throws Exception {
			Map<Long, SignedVertex> inputComponent = input.getMap().get(inputKey);
			Map<Long, SignedVertex> selfComponent = candidate.getMap().get(selfKey);

			// Find the vertices to merge along
			List<Long> mergeBy = new ArrayList<>();

			for (long inputVertex : inputComponent.keySet()) {
				if (selfComponent.containsKey(inputVertex)) {
					mergeBy.add(inputVertex);
				}
			}

			if (mergeBy.isEmpty()) {
				System.out.println(selfComponent);
				System.out.println(inputComponent);
			}

			// Determine if the merge should be with reversed signs or not
			boolean inputSign = inputComponent.get(mergeBy.get(0)).getSign();
			boolean selfSign = selfComponent.get(mergeBy.get(0)).getSign();
			boolean reversed = inputSign != selfSign;

			// Evaluate the merge
			boolean success = true;
			for (long mergeVertex : mergeBy) {
				inputSign = inputComponent.get(mergeVertex).getSign();
				selfSign = selfComponent.get(mergeVertex).getSign();
				if (reversed) {
					success = inputSign != selfSign;
				} else {
					success = inputSign == selfSign;
				}
				if (!success) {
					return false;
				}
			}

			// Execute the merge
			long commonKey = Math.min(inputKey, selfKey);

			// Merge input vertices
			for (SignedVertex inputVertex : inputComponent.values()) {

				if (reversed) {
					success = candidate.add(commonKey, inputVertex.reverse());
				} else {
					success = candidate.add(commonKey, inputVertex);
				}
				if (!success) {
					return false;
				}
			}

			return true;
		}

		private Candidate fail() {
			failed = true;
			return new Candidate(self, false);
		}
	}

	private static final class BipartitenessMapper extends RichMapFunction<Candidate, Candidate> {
		private Candidate candidate = null;
		private boolean failed = false;
		private int self;

		public Candidate map(Candidate input) throws Exception {
			self = getRuntimeContext().getIndexOfThisSubtask();

			// Propagate failure
			if (!input.getSuccess() || failed) {
				return fail();
			}

			// Store and forward the first candidate
			if (candidate == null) {
				candidate = new Candidate(self, true, input);
				return candidate;
			}

			// Compare each input component with each candidate component and merge accordingly
			for (Map.Entry<Long, Map<Long, SignedVertex>> inEntry : input.getMap().entrySet()) {

				List<Long> mergeWith = new ArrayList<>();

				for (Map.Entry<Long, Map<Long, SignedVertex>> selfEntry : candidate.getMap().entrySet()) {
					long selfKey = selfEntry.getKey();

					// If the two components are exactly the same, skip them
					if (inEntry.getValue().keySet().containsAll(selfEntry.getValue().keySet())
							&& selfEntry.getValue().keySet().containsAll(inEntry.getValue().keySet())) {
						continue;
					}

					// Find vertices of input component in the candidate component
					for (long inVertex : inEntry.getValue().keySet()) {
						if (selfEntry.getValue().containsKey(inVertex)) {
							if (!mergeWith.contains(selfKey)) {
								mergeWith.add(selfKey);
								break;
							}
						}
					}
				}

				if (mergeWith.isEmpty()) {
					// If the input component is disjoint from all components of the candidate,
					// simply add that component
					candidate.add(inEntry.getKey(), inEntry.getValue());
				} else {
					// Merge the input with the lowest id component in candidate
					Collections.sort(mergeWith);
					long firstKey = mergeWith.get(0);
					boolean success;

					success = merge(input, inEntry.getKey(), firstKey);
					if (!success) {
						return fail();
					}

					// Merge other components of candidate into the lowest id component
					for (int i = 1; i < mergeWith.size(); ++i) {

						success = merge(candidate, mergeWith.get(i), firstKey);
						if (!success) {
							fail();
						}

						candidate.getMap().remove(mergeWith.get(i));
					}
				}
			}

			return candidate;
		}

		private boolean merge(Candidate input, long inputKey, long selfKey) throws Exception {
			Map<Long, SignedVertex> inputComponent = input.getMap().get(inputKey);
			Map<Long, SignedVertex> selfComponent = candidate.getMap().get(selfKey);

			// Find the vertices to merge along
			List<Long> mergeBy = new ArrayList<>();

			for (long inputVertex : inputComponent.keySet()) {
				if (selfComponent.containsKey(inputVertex)) {
					mergeBy.add(inputVertex);
				}
			}

			// Determine if the merge should be with reversed signs or not
			boolean inputSign = inputComponent.get(mergeBy.get(0)).getSign();
			boolean selfSign = selfComponent.get(mergeBy.get(0)).getSign();
			boolean reversed = inputSign != selfSign;

			// Evaluate the merge
			boolean success = true;
			for (long mergeVertex : mergeBy) {
				inputSign = inputComponent.get(mergeVertex).getSign();
				selfSign = selfComponent.get(mergeVertex).getSign();
				if (reversed) {
					success = inputSign != selfSign;
				} else {
					success = inputSign == selfSign;
				}
				if (!success) {
					return false;
				}
			}

			// Execute the merge
			long commonKey = Math.min(inputKey, selfKey);

			// Merge input vertices
			for (SignedVertex inputVertex : inputComponent.values()) {

				if (reversed) {
					success = candidate.add(commonKey, inputVertex.reverse());
				} else {
					success = candidate.add(commonKey, inputVertex);
				}
				if (!success) {
					return false;
				}
			}

			return true;
		}

		private Candidate fail() {
			failed = true;
			return new Candidate(self, false);
		}
	}

	private static final class MergeTreeKeySelector implements KeySelector<Candidate, Integer> {
		private int level;

		public MergeTreeKeySelector(int level) {
			this.level = level;
		}

		@Override
		public Integer getKey(Candidate input) throws Exception {
			return input.getSource() >> (level + 1);
		}
	}

	private static final class InitCandidateMapper implements
			MapFunction<Edge<Long,NullValue>, Candidate> {

		@Override
		public Candidate map(Edge<Long, NullValue> edge) throws Exception {
			long src = Math.min(edge.getSource(), edge.getTarget());
			long trg = Math.max(edge.getSource(), edge.getTarget());

			Candidate candidate = new Candidate(0, true);
			candidate.add(src, new SignedVertex(src, true));
			candidate.add(src, new SignedVertex(trg, false));

			return candidate;
		}
	}

	public static void main(String[] args) throws Exception {

		Map<Integer, Long> results = new HashMap<>();

		for (int w = 1000; w < 300000; w += 1000) {
			System.out.println("Measuring " + w);
			long sum = 0;
			for (int i = 0; i < 3; ++i) {
				sum += WindowedBipartiteMergeTreeMeasurement.measure(w);
			}
			results.put(w, sum / 3);
		}

		System.out.println(results);
	}
}