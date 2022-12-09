/*
 * Copyright (C) 2022, Tencent.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.revwalk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.commitgraph.CommitGraph;
import org.eclipse.jgit.internal.storage.file.GC;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.filter.MessageRevFilter;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.junit.Test;

public class RevWalkCommitGraphTest extends RevWalkTestCase {

	@Test
	public void testParseHeadersInGraph() throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");

		writeCommitGraph();
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION, true);

		rw.setRetainBody(true);
		rw.dispose();
		RevCommit notParseInGraph = rw.lookupCommit(c1);
		rw.parseHeaders(notParseInGraph);
		assertNotNull(notParseInGraph.getRawBuffer());
		assertEquals(Constants.COMMIT_GENERATION_UNKNOWN,
				notParseInGraph.getGeneration());

		rw.dispose();
		RevCommit parseInGraph = rw.lookupCommit(c1);
		rw.parseHeadersInGraph(parseInGraph);
		assertNull(parseInGraph.getRawBuffer());
		assertEquals(1, parseInGraph.getGeneration());

		assertEquals(notParseInGraph.getId(), parseInGraph.getId());
		assertEquals(notParseInGraph.getTree(), parseInGraph.getTree());
		assertEquals(notParseInGraph.getCommitTime(), parseInGraph.getCommitTime());
		assertArrayEquals(notParseInGraph.getParents(), parseInGraph.getParents());
	}

	@Test
	public void testTreeFilter() throws Exception {
		RevCommit c1 = commitFile("file1", "1", "master");
		RevCommit c2 = commitFile("file2", "2", "master");
		RevCommit c3 = commitFile("file1", "3", "master");
		RevCommit c4 = commitFile("file2", "4", "master");

		writeCommitGraph();
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION, true);
		assertCommitCntInGraph(4);

		rw.markStart(rw.lookupCommit(c4));
		rw.setTreeFilter(AndTreeFilter.create(PathFilter.create("file1"),
				TreeFilter.ANY_DIFF));
		assertEquals(c3, rw.next());
		assertEquals(c1, rw.next());
		assertNull(rw.next());

		rw.dispose();
		rw.markStart(rw.lookupCommit(c4));
		rw.setTreeFilter(AndTreeFilter.create(PathFilter.create("file2"),
				TreeFilter.ANY_DIFF));
		assertEquals(c4, rw.next());
		assertEquals(c2, rw.next());
		assertNull(rw.next());
	}

	@Test
	public void testWalkWithCommitMessageFilter() throws Exception {
		RevCommit a = commit();
		RevCommit b = commitBuilder().parent(a)
				.message("The quick brown fox jumps over the lazy dog!")
				.create();
		RevCommit c = commitBuilder().parent(b).message("commit-c").create();
		branch(c, "master");

		writeCommitGraph();
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION, true);
		assertCommitCntInGraph(3);

		rw.setRevFilter(MessageRevFilter.create("quick brown fox jumps"));
		rw.markStart(c);
		assertCommit(b, rw.next());
		assertNull(rw.next());
	}

	@Test
	public void testCommitsWalk() throws Exception {
		RevCommit c1 = commit();
		branch(c1, "commits/1");
		RevCommit c2 = commit(c1);
		branch(c2, "commits/2");
		RevCommit c3 = commit(c2);
		branch(c3, "commits/3");

		testRevWalkBehavior("commits/1", "commits/3");
		assertCommitCntInGraph(0);

		writeCommitGraph();
		testRevWalkBehavior("commits/1", "commits/3");
		assertCommitCntInGraph(3);

		// add more commits
		RevCommit c4 = commit(c1);
		RevCommit c5 = commit(c4);
		RevCommit c6 = commit(c1);
		RevCommit c7 = commit(c6);

		RevCommit m1 = commit(c2, c4);
		branch(m1, "merge/1");
		RevCommit m2 = commit(c4, c6);
		branch(m2, "merge/2");
		RevCommit m3 = commit(c3, c5, c7);
		branch(m3, "merge/3");

		/*
		 * <pre>
		 * current graph structure:
		 *
		 *    __M3___
		 *   /   |   \
		 *  3 M1 5 M2 7
		 *  |/  \|/  \|
		 *  2    4    6
		 *  |___/____/
		 *  1
		 * </pre>
		 */
		writeCommitGraph();
		assertCommitCntInGraph(10);
		testRevWalkBehavior("merge/1", "merge/2");
		testRevWalkBehavior("merge/1", "merge/3");
		testRevWalkBehavior("merge/2", "merge/3");

		// add one more commit
		RevCommit c8 = commit(m3);
		branch(c8, "commits/8");

		/*
		 * <pre>
		 * current graph structure:
		 *       8
		 *       |
		 *    __M3___
		 *   /   |   \
		 *  3 M1 5 M2 7
		 *  |/  \|/  \|
		 *  2    4    6
		 *  |___/____/
		 *  1
		 * </pre>
		 */
		testRevWalkBehavior("commits/8", "merge/1");
		testRevWalkBehavior("commits/8", "merge/2");

		writeCommitGraph();
		assertCommitCntInGraph(11);
		testRevWalkBehavior("commits/8", "merge/1");
		testRevWalkBehavior("commits/8", "merge/2");
	}

	void writeCommitGraph() throws Exception {
		db.getConfig().setBoolean(ConfigConstants.CONFIG_GC_SECTION, null,
				ConfigConstants.CONFIG_KEY_WRITE_COMMIT_GRAPH, true);
		GC gc = new GC(db);
		gc.gc();
	}

	void assertCommitCntInGraph(int expect) {
		rw.dispose();
		CommitGraph graph = rw.getCommitGraph();

		if (expect <= 0) {
			assertNull(graph);
			return;
		}
		assertNotNull(graph);
		assertEquals(expect, graph.getCommitCnt());
	}

	void testRevWalkBehavior(String branch, String compare) throws Exception {
		rw.setTreeFilter(TreeFilter.ALL);
		rw.setRevFilter(RevFilter.MERGE_BASE);
		testGraphTwoModes(branch, compare);

		rw.setRevFilter(RevFilter.ALL);
		rw.sort(RevSort.TOPO);
		testGraphTwoModes(branch);
		testGraphTwoModes(compare);

		rw.setRevFilter(RevFilter.ALL);
		rw.sort(RevSort.COMMIT_TIME_DESC);
		testGraphTwoModes(branch);
		testGraphTwoModes(compare);
	}

	Ref branch(RevCommit commit, String name) throws Exception {
		return Git.wrap(db).branchCreate().setName(name)
				.setStartPoint(commit.name()).call();
	}

	void testGraphTwoModes(String branch) throws Exception {
		testGraphTwoModes(Collections.singleton(db.resolve(branch)));
	}

	void testGraphTwoModes(String branch, String compare)
			throws Exception {
		List<ObjectId> commits = new ArrayList<>();
		commits.add(db.resolve(branch));
		commits.add(db.resolve(compare));
		testGraphTwoModes(commits);
	}

	void testGraphTwoModes(Collection<ObjectId> starts)
			throws Exception {
		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION, false);
		rw.dispose();

		for (ObjectId start : starts) {
			markStart(rw.lookupCommit(start));
		}
		List<RevCommit> withOutGraph = new ArrayList<>();

		for (RevCommit commit : rw) {
			withOutGraph.add(commit);
		}

		db.getConfig().setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_COMMIT_GRAPH_SECTION, true);
		rw.dispose();

		for (ObjectId start : starts) {
			markStart(rw.lookupCommit(start));
		}
		List<RevCommit> withGraph = new ArrayList<>();

		for (RevCommit commit : rw) {
			withGraph.add(commit);
		}
		rw.dispose();

		assertEquals(withOutGraph.size(), withGraph.size());

		for (int i = 0; i < withGraph.size(); i++) {
			RevCommit expect = withOutGraph.get(i);
			RevCommit commit = withGraph.get(i);

			assertEquals(expect.getId(), commit.getId());
			assertEquals(expect.getTree(), commit.getTree());
			assertEquals(expect.getCommitTime(), commit.getCommitTime());
			assertArrayEquals(expect.getParents(), commit.getParents());
			assertArrayEquals(expect.getRawBuffer(), commit.getRawBuffer());
		}
	}
}
