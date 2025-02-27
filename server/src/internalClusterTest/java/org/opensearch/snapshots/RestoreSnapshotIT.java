/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.snapshots;

import org.opensearch.action.ActionFuture;
import org.opensearch.action.DocWriteResponse;
import org.opensearch.action.admin.cluster.remotestore.restore.RestoreRemoteStoreRequest;
import org.opensearch.action.admin.cluster.snapshots.create.CreateSnapshotResponse;
import org.opensearch.action.admin.cluster.snapshots.restore.RestoreSnapshotResponse;
import org.opensearch.action.admin.indices.get.GetIndexRequest;
import org.opensearch.action.admin.indices.get.GetIndexResponse;
import org.opensearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.opensearch.action.admin.indices.template.delete.DeleteIndexTemplateRequestBuilder;
import org.opensearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.opensearch.action.delete.DeleteResponse;
import org.opensearch.action.index.IndexRequestBuilder;
import org.opensearch.action.support.PlainActionFuture;
import org.opensearch.client.Client;
import org.opensearch.client.Requests;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.MappingMetadata;
import org.opensearch.common.io.PathUtils;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.ByteSizeUnit;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.util.FeatureFlags;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.IndexSettings;
import org.opensearch.indices.InvalidIndexNameException;
import org.opensearch.indices.replication.common.ReplicationType;
import org.opensearch.repositories.RepositoriesService;

import org.opensearch.test.InternalTestCluster;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_REPLICAS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_NUMBER_OF_SHARDS;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_REMOTE_STORE_ENABLED;
import static org.opensearch.cluster.metadata.IndexMetadata.SETTING_REMOTE_SEGMENT_STORE_REPOSITORY;
import static org.opensearch.index.IndexSettings.INDEX_REFRESH_INTERVAL_SETTING;
import static org.opensearch.index.IndexSettings.INDEX_SOFT_DELETES_SETTING;
import static org.opensearch.index.query.QueryBuilders.matchQuery;
import static org.opensearch.indices.recovery.RecoverySettings.INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertHitCount;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertIndexTemplateExists;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertIndexTemplateMissing;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertRequestBuilderThrows;

public class RestoreSnapshotIT extends AbstractSnapshotIntegTestCase {
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal)).put(FeatureFlags.REMOTE_STORE, "true").build();
    }

    public void testParallelRestoreOperations() {
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String repoName = "test-restore-snapshot-repo";
        String snapshotName1 = "test-restore-snapshot1";
        String snapshotName2 = "test-restore-snapshot2";
        Path absolutePath = randomRepoPath().toAbsolutePath();
        logger.info("Path [{}]", absolutePath);
        String restoredIndexName1 = indexName1 + "-restored";
        String restoredIndexName2 = indexName2 + "-restored";
        String expectedValue = "expected";

        Client client = client();
        // Write a document
        String docId = Integer.toString(randomInt());
        index(indexName1, "_doc", docId, "value", expectedValue);

        String docId2 = Integer.toString(randomInt());
        index(indexName2, "_doc", docId2, "value", expectedValue);

        createRepository(repoName, "fs", absolutePath);

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        CreateSnapshotResponse createSnapshotResponse2 = client.admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName2)
            .setWaitForCompletion(true)
            .setIndices(indexName2)
            .get();
        assertThat(createSnapshotResponse2.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse2.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse2.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse2.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        RestoreSnapshotResponse restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName1)
            .setWaitForCompletion(false)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .get();
        RestoreSnapshotResponse restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName2)
            .setWaitForCompletion(false)
            .setRenamePattern(indexName2)
            .setRenameReplacement(restoredIndexName2)
            .get();
        assertThat(restoreSnapshotResponse1.status(), equalTo(RestStatus.ACCEPTED));
        assertThat(restoreSnapshotResponse2.status(), equalTo(RestStatus.ACCEPTED));
        ensureGreen(restoredIndexName1, restoredIndexName2);
        assertThat(client.prepareGet(restoredIndexName1, docId).get().isExists(), equalTo(true));
        assertThat(client.prepareGet(restoredIndexName2, docId2).get().isExists(), equalTo(true));
    }

    public void testRestoreRemoteStoreIndicesWithRemoteTranslog() throws IOException, ExecutionException, InterruptedException {
        testRestoreOperationsShallowCopyEnabled();
    }

    public void testRestoreOperationsShallowCopyEnabled() throws IOException, ExecutionException, InterruptedException {
        String clusterManagerNode = internalCluster().startClusterManagerOnlyNode();
        String primary = internalCluster().startDataOnlyNode();
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String snapshotRepoName = "test-restore-snapshot-repo";
        String remoteStoreRepoName = "test-rs-repo" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String snapshotName1 = "test-restore-snapshot1";
        String snapshotName2 = "test-restore-snapshot2";
        Path absolutePath1 = randomRepoPath().toAbsolutePath();
        Path absolutePath2 = randomRepoPath().toAbsolutePath();
        logger.info("Snapshot Path [{}]", absolutePath1);
        logger.info("Remote Store Repo Path [{}]", absolutePath2);
        String restoredIndexName1 = indexName1 + "-restored";
        String restoredIndexName1Seg = indexName1 + "-restored-seg";
        String restoredIndexName1Doc = indexName1 + "-restored-doc";
        String restoredIndexName2 = indexName2 + "-restored";

        createRepository(snapshotRepoName, "fs", getRepositorySettings(absolutePath1, true));
        createRepository(remoteStoreRepoName, "fs", absolutePath2);

        Client client = client();
        Settings indexSettings = getIndexSettings(true, remoteStoreRepoName, 1, 0).build();
        createIndex(indexName1, indexSettings);

        Settings indexSettings2 = getIndexSettings(false, null, 1, 0).build();
        createIndex(indexName2, indexSettings2);

        final int numDocsInIndex1 = 5;
        final int numDocsInIndex2 = 6;
        indexDocuments(client, indexName1, numDocsInIndex1);
        indexDocuments(client, indexName2, numDocsInIndex2);
        ensureGreen(indexName1, indexName2);

        internalCluster().startDataOnlyNode();
        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        updateRepository(snapshotRepoName, "fs", getRepositorySettings(absolutePath1, false));
        CreateSnapshotResponse createSnapshotResponse2 = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName2)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse2.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse2.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse2.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse2.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        DeleteResponse deleteResponse = client().prepareDelete(indexName1, "0").execute().actionGet();
        assertEquals(deleteResponse.getResult(), DocWriteResponse.Result.DELETED);
        indexDocuments(client, indexName1, numDocsInIndex1, numDocsInIndex1 + randomIntBetween(2, 5));
        ensureGreen(indexName1);

        RestoreSnapshotResponse restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(false)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .get();
        RestoreSnapshotResponse restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName2)
            .setWaitForCompletion(false)
            .setIndices(indexName2)
            .setRenamePattern(indexName2)
            .setRenameReplacement(restoredIndexName2)
            .get();
        assertEquals(restoreSnapshotResponse1.status(), RestStatus.ACCEPTED);
        assertEquals(restoreSnapshotResponse2.status(), RestStatus.ACCEPTED);
        ensureGreen(restoredIndexName1, restoredIndexName2);
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1);
        assertDocsPresentInIndex(client, restoredIndexName2, numDocsInIndex2);

        // deleting data for restoredIndexName1 and restoring from remote store.
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primary));
        ensureRed(restoredIndexName1);
        // Re-initialize client to make sure we are not using client from stopped node.
        client = client(clusterManagerNode);
        assertAcked(client.admin().indices().prepareClose(restoredIndexName1));
        client.admin()
            .cluster()
            .restoreRemoteStore(new RestoreRemoteStoreRequest().indices(restoredIndexName1), PlainActionFuture.newFuture());
        ensureYellowAndNoInitializingShards(restoredIndexName1);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client(), restoredIndexName1, numDocsInIndex1);
        // indexing some new docs and validating
        indexDocuments(client, restoredIndexName1, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1 + 2);

        // restore index as seg rep enabled with remote store and remote translog disabled
        RestoreSnapshotResponse restoreSnapshotResponse3 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(false)
            .setIgnoreIndexSettings(IndexMetadata.SETTING_REMOTE_STORE_ENABLED)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1Seg)
            .get();
        assertEquals(restoreSnapshotResponse3.status(), RestStatus.ACCEPTED);
        ensureGreen(restoredIndexName1Seg);

        GetIndexResponse getIndexResponse = client.admin()
            .indices()
            .getIndex(new GetIndexRequest().indices(restoredIndexName1Seg).includeDefaults(true))
            .get();
        indexSettings = getIndexResponse.settings().get(restoredIndexName1Seg);
        assertNull(indexSettings.get(SETTING_REMOTE_STORE_ENABLED));
        assertNull(indexSettings.get(SETTING_REMOTE_SEGMENT_STORE_REPOSITORY, null));
        assertEquals(ReplicationType.SEGMENT.toString(), indexSettings.get(IndexMetadata.SETTING_REPLICATION_TYPE));
        assertDocsPresentInIndex(client, restoredIndexName1Seg, numDocsInIndex1);
        // indexing some new docs and validating
        indexDocuments(client, restoredIndexName1Seg, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(restoredIndexName1Seg);
        assertDocsPresentInIndex(client, restoredIndexName1Seg, numDocsInIndex1 + 2);

        // restore index as doc rep based from shallow copy snapshot
        RestoreSnapshotResponse restoreSnapshotResponse4 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(false)
            .setIgnoreIndexSettings(IndexMetadata.SETTING_REMOTE_STORE_ENABLED, IndexMetadata.SETTING_REPLICATION_TYPE)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1Doc)
            .get();
        assertEquals(restoreSnapshotResponse4.status(), RestStatus.ACCEPTED);
        ensureGreen(restoredIndexName1Doc);

        getIndexResponse = client.admin()
            .indices()
            .getIndex(new GetIndexRequest().indices(restoredIndexName1Doc).includeDefaults(true))
            .get();
        indexSettings = getIndexResponse.settings().get(restoredIndexName1Doc);
        assertNull(indexSettings.get(SETTING_REMOTE_STORE_ENABLED));
        assertNull(indexSettings.get(SETTING_REMOTE_SEGMENT_STORE_REPOSITORY, null));
        assertNull(indexSettings.get(IndexMetadata.SETTING_REPLICATION_TYPE));
        assertDocsPresentInIndex(client, restoredIndexName1Doc, numDocsInIndex1);
        // indexing some new docs and validating
        indexDocuments(client, restoredIndexName1Doc, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(restoredIndexName1Doc);
        assertDocsPresentInIndex(client, restoredIndexName1Doc, numDocsInIndex1 + 2);
    }

    public void testRestoreInSameRemoteStoreEnabledIndex() throws IOException {
        String clusterManagerNode = internalCluster().startClusterManagerOnlyNode();
        String primary = internalCluster().startDataOnlyNode();
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String snapshotRepoName = "test-restore-snapshot-repo";
        String remoteStoreRepoName = "test-rs-repo" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String snapshotName1 = "test-restore-snapshot1";
        String snapshotName2 = "test-restore-snapshot2";
        Path absolutePath1 = randomRepoPath().toAbsolutePath();
        Path absolutePath2 = randomRepoPath().toAbsolutePath();
        logger.info("Snapshot Path [{}]", absolutePath1);
        logger.info("Remote Store Repo Path [{}]", absolutePath2);
        String restoredIndexName2 = indexName2 + "-restored";

        boolean enableShallowCopy = randomBoolean();
        createRepository(snapshotRepoName, "fs", getRepositorySettings(absolutePath1, enableShallowCopy));
        createRepository(remoteStoreRepoName, "fs", absolutePath2);

        Client client = client();
        Settings indexSettings = getIndexSettings(true, remoteStoreRepoName, 1, 0).build();
        createIndex(indexName1, indexSettings);

        Settings indexSettings2 = getIndexSettings(false, null, 1, 0).build();
        createIndex(indexName2, indexSettings2);

        final int numDocsInIndex1 = 5;
        final int numDocsInIndex2 = 6;
        indexDocuments(client, indexName1, numDocsInIndex1);
        indexDocuments(client, indexName2, numDocsInIndex2);
        ensureGreen(indexName1, indexName2);

        internalCluster().startDataOnlyNode();
        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        updateRepository(snapshotRepoName, "fs", getRepositorySettings(absolutePath1, false));
        CreateSnapshotResponse createSnapshotResponse2 = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName2)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse2.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse2.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse2.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse2.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        DeleteResponse deleteResponse = client().prepareDelete(indexName1, "0").execute().actionGet();
        assertEquals(deleteResponse.getResult(), DocWriteResponse.Result.DELETED);
        indexDocuments(client, indexName1, numDocsInIndex1, numDocsInIndex1 + randomIntBetween(2, 5));
        ensureGreen(indexName1);

        assertAcked(client().admin().indices().prepareClose(indexName1));

        RestoreSnapshotResponse restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(false)
            .setIndices(indexName1)
            .get();
        RestoreSnapshotResponse restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName2)
            .setWaitForCompletion(false)
            .setIndices(indexName2)
            .setRenamePattern(indexName2)
            .setRenameReplacement(restoredIndexName2)
            .get();
        assertEquals(restoreSnapshotResponse1.status(), RestStatus.ACCEPTED);
        assertEquals(restoreSnapshotResponse2.status(), RestStatus.ACCEPTED);
        ensureGreen(indexName1, restoredIndexName2);
        assertDocsPresentInIndex(client, indexName1, numDocsInIndex1);
        assertDocsPresentInIndex(client, restoredIndexName2, numDocsInIndex2);

        // deleting data for restoredIndexName1 and restoring from remote store.
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primary));
        ensureRed(indexName1);
        // Re-initialize client to make sure we are not using client from stopped node.
        client = client(clusterManagerNode);
        assertAcked(client.admin().indices().prepareClose(indexName1));
        client.admin().cluster().restoreRemoteStore(new RestoreRemoteStoreRequest().indices(indexName1), PlainActionFuture.newFuture());
        ensureYellowAndNoInitializingShards(indexName1);
        ensureGreen(indexName1);
        assertDocsPresentInIndex(client(), indexName1, numDocsInIndex1);
        // indexing some new docs and validating
        indexDocuments(client, indexName1, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(indexName1);
        assertDocsPresentInIndex(client, indexName1, numDocsInIndex1 + 2);
    }

    public void testRestoreShallowCopySnapshotWithDifferentRepo() throws IOException {
        String clusterManagerNode = internalCluster().startClusterManagerOnlyNode();
        String primary = internalCluster().startDataOnlyNode();
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String snapshotRepoName = "test-restore-snapshot-repo";
        String remoteStoreRepoName = "test-rs-repo" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String remoteStoreRepo2Name = "test-rs-repo-2" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String snapshotName1 = "test-restore-snapshot1";
        Path absolutePath1 = randomRepoPath().toAbsolutePath();
        Path absolutePath2 = randomRepoPath().toAbsolutePath();
        Path absolutePath3 = randomRepoPath().toAbsolutePath();
        String restoredIndexName1 = indexName1 + "-restored";

        createRepository(snapshotRepoName, "fs", getRepositorySettings(absolutePath1, false));
        createRepository(remoteStoreRepoName, "fs", absolutePath2);
        createRepository(remoteStoreRepo2Name, "fs", absolutePath3);

        Client client = client();
        Settings indexSettings = getIndexSettings(true, remoteStoreRepoName, 1, 0).build();
        createIndex(indexName1, indexSettings);

        Settings indexSettings2 = getIndexSettings(false, null, 1, 0).build();
        createIndex(indexName2, indexSettings2);

        final int numDocsInIndex1 = 5;
        final int numDocsInIndex2 = 6;
        indexDocuments(client, indexName1, numDocsInIndex1);
        indexDocuments(client, indexName2, numDocsInIndex2);
        ensureGreen(indexName1, indexName2);

        internalCluster().startDataOnlyNode();

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        Settings remoteStoreIndexSettings = Settings.builder()
            .put(IndexMetadata.SETTING_REMOTE_SEGMENT_STORE_REPOSITORY, remoteStoreRepo2Name)
            .build();
        // restore index as a remote store index with different remote store repo
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(false)
            .setIndexSettings(remoteStoreIndexSettings)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .get();
        assertEquals(restoreSnapshotResponse.status(), RestStatus.ACCEPTED);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client(), restoredIndexName1, numDocsInIndex1);

        // deleting data for restoredIndexName1 and restoring from remote store.
        internalCluster().stopRandomNode(InternalTestCluster.nameFilter(primary));
        // Re-initialize client to make sure we are not using client from stopped node.
        client = client(clusterManagerNode);
        assertAcked(client.admin().indices().prepareClose(restoredIndexName1));
        client.admin()
            .cluster()
            .restoreRemoteStore(new RestoreRemoteStoreRequest().indices(restoredIndexName1), PlainActionFuture.newFuture());
        ensureYellowAndNoInitializingShards(restoredIndexName1);
        ensureGreen(restoredIndexName1);
        // indexing some new docs and validating
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1);
        indexDocuments(client, restoredIndexName1, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1 + 2);
    }

    private Settings.Builder getIndexSettings(boolean enableRemoteStore, String remoteStoreRepo, int numOfShards, int numOfReplicas) {
        Settings.Builder settingsBuilder = Settings.builder()
            .put(super.indexSettings())
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, numOfShards)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, numOfReplicas);
        if (enableRemoteStore) {
            settingsBuilder.put(IndexMetadata.SETTING_REMOTE_STORE_ENABLED, true)
                .put(IndexMetadata.SETTING_REMOTE_SEGMENT_STORE_REPOSITORY, remoteStoreRepo)
                .put(IndexMetadata.SETTING_REMOTE_TRANSLOG_STORE_REPOSITORY, remoteStoreRepo)
                .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), "300s")
                .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT);
        }
        return settingsBuilder;
    }

    public void testRestoreShallowSnapshotRepositoryOverriden() throws ExecutionException, InterruptedException {
        String indexName1 = "testindex1";
        String snapshotRepoName = "test-restore-snapshot-repo";
        String remoteStoreRepoName = "test-rs-repo" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String remoteStoreRepoNameUpdated = "test-rs-repo-updated" + TEST_REMOTE_STORE_REPO_SUFFIX;
        String snapshotName1 = "test-restore-snapshot1";
        Path absolutePath1 = randomRepoPath().toAbsolutePath();
        Path absolutePath2 = randomRepoPath().toAbsolutePath();
        Path absolutePath3 = randomRepoPath().toAbsolutePath();
        String[] pathTokens = absolutePath1.toString().split("/");
        String basePath = pathTokens[pathTokens.length - 1];
        Arrays.copyOf(pathTokens, pathTokens.length - 1);
        Path location = PathUtils.get(String.join("/", pathTokens));
        pathTokens = absolutePath2.toString().split("/");
        String basePath2 = pathTokens[pathTokens.length - 1];
        Arrays.copyOf(pathTokens, pathTokens.length - 1);
        Path location2 = PathUtils.get(String.join("/", pathTokens));
        logger.info("Path 1 [{}]", absolutePath1);
        logger.info("Path 2 [{}]", absolutePath2);
        logger.info("Path 3 [{}]", absolutePath3);
        String restoredIndexName1 = indexName1 + "-restored";

        createRepository(snapshotRepoName, "fs", getRepositorySettings(location, basePath, true));
        createRepository(remoteStoreRepoName, "fs", absolutePath3);

        Client client = client();
        Settings indexSettings = Settings.builder()
            .put(super.indexSettings())
            .put(IndexMetadata.SETTING_REMOTE_STORE_ENABLED, true)
            .put(IndexMetadata.SETTING_REMOTE_SEGMENT_STORE_REPOSITORY, remoteStoreRepoName)
            .put(IndexMetadata.SETTING_REMOTE_TRANSLOG_STORE_REPOSITORY, remoteStoreRepoName)
            .put(IndexSettings.INDEX_REFRESH_INTERVAL_SETTING.getKey(), "300s")
            .put(IndexMetadata.SETTING_REPLICATION_TYPE, ReplicationType.SEGMENT)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .build();
        createIndex(indexName1, indexSettings);

        int numDocsInIndex1 = randomIntBetween(2, 5);
        indexDocuments(client, indexName1, numDocsInIndex1);

        ensureGreen(indexName1);

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        createRepository(remoteStoreRepoName, "fs", absolutePath2);

        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .get();

        assertTrue(restoreSnapshotResponse.getRestoreInfo().failedShards() > 0);

        ensureRed(restoredIndexName1);

        client().admin().indices().close(Requests.closeIndexRequest(restoredIndexName1)).get();
        createRepository(remoteStoreRepoNameUpdated, "fs", absolutePath3);
        RestoreSnapshotResponse restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(snapshotRepoName, snapshotName1)
            .setWaitForCompletion(true)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .setSourceRemoteStoreRepository(remoteStoreRepoNameUpdated)
            .get();

        assertTrue(restoreSnapshotResponse2.getRestoreInfo().failedShards() == 0);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1);

        // indexing some new docs and validating
        indexDocuments(client, restoredIndexName1, numDocsInIndex1, numDocsInIndex1 + 2);
        ensureGreen(restoredIndexName1);
        assertDocsPresentInIndex(client, restoredIndexName1, numDocsInIndex1 + 2);
    }

    private void indexDocuments(Client client, String indexName, int numOfDocs) {
        indexDocuments(client, indexName, 0, numOfDocs);
    }

    private void indexDocuments(Client client, String indexName, int fromId, int toId) {
        for (int i = fromId; i < toId; i++) {
            String id = Integer.toString(i);
            client.prepareIndex(indexName).setId(id).setSource("text", "sometext").get();
        }
        client.admin().indices().prepareFlush(indexName).get();
    }

    private void assertDocsPresentInIndex(Client client, String indexName, int numOfDocs) {
        for (int i = 0; i < numOfDocs; i++) {
            String id = Integer.toString(i);
            logger.info("checking for index " + indexName + " with docId" + id);
            assertTrue("doc with id" + id + " is not present for index " + indexName, client.prepareGet(indexName, id).get().isExists());
        }
    }

    public void testParallelRestoreOperationsFromSingleSnapshot() throws Exception {
        String indexName1 = "testindex1";
        String indexName2 = "testindex2";
        String repoName = "test-restore-snapshot-repo";
        String snapshotName = "test-restore-snapshot";
        Path absolutePath = randomRepoPath().toAbsolutePath();
        logger.info("Path [{}]", absolutePath);
        String restoredIndexName1 = indexName1 + "-restored";
        String restoredIndexName2 = indexName2 + "-restored";
        String expectedValue = "expected";

        Client client = client();
        // Write a document
        String docId = Integer.toString(randomInt());
        index(indexName1, "_doc", docId, "value", expectedValue);

        String docId2 = Integer.toString(randomInt());
        index(indexName2, "_doc", docId2, "value", expectedValue);

        createRepository(repoName, "fs", absolutePath);

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot(repoName, snapshotName)
            .setWaitForCompletion(true)
            .setIndices(indexName1, indexName2)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );
        assertThat(createSnapshotResponse.getSnapshotInfo().state(), equalTo(SnapshotState.SUCCESS));

        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse1 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setIndices(indexName1)
            .setRenamePattern(indexName1)
            .setRenameReplacement(restoredIndexName1)
            .execute();

        boolean sameSourceIndex = randomBoolean();

        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse2 = client.admin()
            .cluster()
            .prepareRestoreSnapshot(repoName, snapshotName)
            .setIndices(sameSourceIndex ? indexName1 : indexName2)
            .setRenamePattern(sameSourceIndex ? indexName1 : indexName2)
            .setRenameReplacement(restoredIndexName2)
            .execute();
        assertThat(restoreSnapshotResponse1.get().status(), equalTo(RestStatus.ACCEPTED));
        assertThat(restoreSnapshotResponse2.get().status(), equalTo(RestStatus.ACCEPTED));
        ensureGreen(restoredIndexName1, restoredIndexName2);
        assertThat(client.prepareGet(restoredIndexName1, docId).get().isExists(), equalTo(true));
        assertThat(client.prepareGet(restoredIndexName2, sameSourceIndex ? docId : docId2).get().isExists(), equalTo(true));
    }

    public void testRestoreIncreasesPrimaryTerms() {
        final String indexName = randomAlphaOfLengthBetween(5, 10).toLowerCase(Locale.ROOT);
        createIndex(indexName, indexSettingsNoReplicas(2).build());
        ensureGreen(indexName);

        if (randomBoolean()) {
            // open and close the index to increase the primary terms
            for (int i = 0; i < randomInt(3); i++) {
                assertAcked(client().admin().indices().prepareClose(indexName));
                assertAcked(client().admin().indices().prepareOpen(indexName));
            }
        }

        final IndexMetadata indexMetadata = clusterAdmin().prepareState()
            .clear()
            .setIndices(indexName)
            .setMetadata(true)
            .get()
            .getState()
            .metadata()
            .index(indexName);
        assertThat(indexMetadata.getSettings().get(IndexMetadata.SETTING_HISTORY_UUID), nullValue());
        final int numPrimaries = getNumShards(indexName).numPrimaries;
        final Map<Integer, Long> primaryTerms = IntStream.range(0, numPrimaries)
            .boxed()
            .collect(Collectors.toMap(shardId -> shardId, indexMetadata::primaryTerm));

        createRepository("test-repo", "fs");
        final CreateSnapshotResponse createSnapshotResponse = clusterAdmin().prepareCreateSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setIndices(indexName)
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), equalTo(numPrimaries));
        assertThat(createSnapshotResponse.getSnapshotInfo().failedShards(), equalTo(0));

        assertAcked(client().admin().indices().prepareClose(indexName));

        final RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .get();
        assertThat(restoreSnapshotResponse.getRestoreInfo().successfulShards(), equalTo(numPrimaries));
        assertThat(restoreSnapshotResponse.getRestoreInfo().failedShards(), equalTo(0));

        final IndexMetadata restoredIndexMetadata = clusterAdmin().prepareState()
            .clear()
            .setIndices(indexName)
            .setMetadata(true)
            .get()
            .getState()
            .metadata()
            .index(indexName);
        for (int shardId = 0; shardId < numPrimaries; shardId++) {
            assertThat(restoredIndexMetadata.primaryTerm(shardId), greaterThan(primaryTerms.get(shardId)));
        }
        assertThat(restoredIndexMetadata.getSettings().get(IndexMetadata.SETTING_HISTORY_UUID), notNullValue());
    }

    public void testRestoreWithDifferentMappingsAndSettings() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("--> create index with baz field");
        assertAcked(
            prepareCreate(
                "test-idx",
                2,
                Settings.builder()
                    .put(indexSettings())
                    .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
                    .put("refresh_interval", 10, TimeUnit.SECONDS)
            )
        );

        NumShards numShards = getNumShards("test-idx");

        assertAcked(client().admin().indices().preparePutMapping("test-idx").setSource("baz", "type=text"));
        ensureGreen();

        logger.info("--> snapshot it");
        CreateSnapshotResponse createSnapshotResponse = clusterAdmin().prepareCreateSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setIndices("test-idx")
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );

        logger.info("--> delete the index and recreate it with foo field");
        cluster().wipeIndices("test-idx");
        assertAcked(
            prepareCreate(
                "test-idx",
                2,
                Settings.builder()
                    .put(SETTING_NUMBER_OF_SHARDS, numShards.numPrimaries)
                    .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
                    .put("refresh_interval", 5, TimeUnit.SECONDS)
            )
        );
        assertAcked(client().admin().indices().preparePutMapping("test-idx").setSource("foo", "type=text"));
        ensureGreen();

        logger.info("--> close index");
        client().admin().indices().prepareClose("test-idx").get();

        logger.info("--> restore all indices from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that old mapping is restored");
        MappingMetadata mappings = clusterAdmin().prepareState().get().getState().getMetadata().getIndices().get("test-idx").mapping();
        assertThat(mappings.sourceAsMap().toString(), containsString("baz"));
        assertThat(mappings.sourceAsMap().toString(), not(containsString("foo")));

        logger.info("--> assert that old settings are restored");
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", "index.refresh_interval"), equalTo("10s"));
    }

    public void testRestoreAliases() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("--> create test indices");
        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        logger.info("--> create aliases");
        assertAcked(
            client().admin()
                .indices()
                .prepareAliases()
                .addAlias("test-idx-1", "alias-123")
                .addAlias("test-idx-2", "alias-123")
                .addAlias("test-idx-3", "alias-123")
                .addAlias("test-idx-1", "alias-1")
                .get()
        );

        assertFalse(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());

        logger.info("--> snapshot");
        assertThat(
            clusterAdmin().prepareCreateSnapshot("test-repo", "test-snap")
                .setIndices()
                .setWaitForCompletion(true)
                .get()
                .getSnapshotInfo()
                .state(),
            equalTo(SnapshotState.SUCCESS)
        );

        logger.info("-->  delete all indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2", "test-idx-3");
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("--> restore snapshot with aliases");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(
            restoreSnapshotResponse.getRestoreInfo().successfulShards(),
            allOf(greaterThan(0), equalTo(restoreSnapshotResponse.getRestoreInfo().totalShards()))
        );

        logger.info("--> check that aliases are restored");
        assertFalse(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertFalse(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("-->  update aliases");
        assertAcked(client().admin().indices().prepareAliases().removeAlias("test-idx-3", "alias-123"));
        assertAcked(client().admin().indices().prepareAliases().addAlias("test-idx-3", "alias-3"));

        logger.info("-->  delete and close indices");
        cluster().wipeIndices("test-idx-1", "test-idx-2");
        assertAcked(client().admin().indices().prepareClose("test-idx-3"));
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());

        logger.info("--> restore snapshot without aliases");
        restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .setIncludeAliases(false)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(
            restoreSnapshotResponse.getRestoreInfo().successfulShards(),
            allOf(greaterThan(0), equalTo(restoreSnapshotResponse.getRestoreInfo().totalShards()))
        );

        logger.info("--> check that aliases are not restored and existing aliases still exist");
        assertTrue(client().admin().indices().prepareGetAliases("alias-123").get().getAliases().isEmpty());
        assertTrue(client().admin().indices().prepareGetAliases("alias-1").get().getAliases().isEmpty());
        assertFalse(client().admin().indices().prepareGetAliases("alias-3").get().getAliases().isEmpty());
    }

    public void testRestoreTemplates() throws Exception {
        createRepository("test-repo", "fs");

        logger.info("-->  creating test template");
        assertThat(
            client().admin()
                .indices()
                .preparePutTemplate("test-template")
                .setPatterns(Collections.singletonList("te*"))
                .setMapping(
                    XContentFactory.jsonBuilder()
                        .startObject()
                        .startObject("properties")
                        .startObject("field1")
                        .field("type", "text")
                        .field("store", true)
                        .endObject()
                        .startObject("field2")
                        .field("type", "keyword")
                        .field("store", true)
                        .endObject()
                        .endObject()
                        .endObject()
                )
                .get()
                .isAcknowledged(),
            equalTo(true)
        );

        logger.info("--> snapshot");
        final SnapshotInfo snapshotInfo = assertSuccessful(
            clusterAdmin().prepareCreateSnapshot("test-repo", "test-snap").setIndices().setWaitForCompletion(true).execute()
        );
        assertThat(snapshotInfo.totalShards(), equalTo(0));
        assertThat(snapshotInfo.successfulShards(), equalTo(0));
        assertThat(getSnapshot("test-repo", "test-snap").state(), equalTo(SnapshotState.SUCCESS));

        logger.info("-->  delete test template");
        assertThat(client().admin().indices().prepareDeleteTemplate("test-template").get().isAcknowledged(), equalTo(true));
        GetIndexTemplatesResponse getIndexTemplatesResponse = client().admin().indices().prepareGetTemplates().get();
        assertIndexTemplateMissing(getIndexTemplatesResponse, "test-template");

        logger.info("--> restore cluster state");
        RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setRestoreGlobalState(true)
            .execute()
            .actionGet();
        // We don't restore any indices here
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), equalTo(0));

        logger.info("--> check that template is restored");
        getIndexTemplatesResponse = client().admin().indices().prepareGetTemplates().get();
        assertIndexTemplateExists(getIndexTemplatesResponse, "test-template");
    }

    public void testRenameOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        createIndex("test-idx-1", "test-idx-2", "test-idx-3");
        ensureGreen();

        assertAcked(
            client.admin()
                .indices()
                .prepareAliases()
                .addAlias("test-idx-1", "alias-1", false)
                .addAlias("test-idx-2", "alias-2", false)
                .addAlias("test-idx-3", "alias-3", false)
        );

        indexRandomDocs("test-idx-1", 100);
        indexRandomDocs("test-idx-2", 100);

        logger.info("--> snapshot");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setIndices("test-idx-1", "test-idx-2")
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );

        logger.info("--> restore indices with different names");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        assertDocCount("test-idx-1-copy", 100L);
        assertDocCount("test-idx-2-copy", 100L);

        logger.info("--> close just restored indices");
        client.admin().indices().prepareClose("test-idx-1-copy", "test-idx-2-copy").get();

        logger.info("--> and try to restore these indices again");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        assertDocCount("test-idx-1-copy", 100L);
        assertDocCount("test-idx-2-copy", 100L);

        logger.info("--> close indices");
        assertAcked(client.admin().indices().prepareClose("test-idx-1", "test-idx-2-copy"));

        logger.info("--> restore indices with different names");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setRenamePattern("(.+-2)")
            .setRenameReplacement("$1-copy")
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> delete indices");
        cluster().wipeIndices("test-idx-1", "test-idx-1-copy", "test-idx-2", "test-idx-2-copy");

        logger.info("--> try renaming indices using the same name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setRenamePattern("(.+)")
                .setRenameReplacement("same-name")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices using the same name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setRenamePattern("test-idx-2")
                .setRenameReplacement("test-idx-1")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices using invalid index name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern(".+")
                .setRenameReplacement("__WRONG__")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (InvalidIndexNameException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias name");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern(".+")
                .setRenameReplacement("alias-3")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (InvalidIndexNameException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of itself");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1")
                .setRenamePattern("test-idx")
                .setRenameReplacement("alias")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of another restored index");
        try {
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndices("test-idx-1", "test-idx-2")
                .setRenamePattern("test-idx-1")
                .setRenameReplacement("alias-2")
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            fail("Shouldn't be here");
        } catch (SnapshotRestoreException ex) {
            // Expected
        }

        logger.info("--> try renaming indices into existing alias of itself, but don't restore aliases ");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIndices("test-idx-1")
            .setRenamePattern("test-idx")
            .setRenameReplacement("alias")
            .setWaitForCompletion(true)
            .setIncludeAliases(false)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
    }

    public void testDynamicRestoreThrottling() throws Exception {
        Client client = client();

        createRepository(
            "test-repo",
            "fs",
            Settings.builder().put("location", randomRepoPath()).put("compress", randomBoolean()).put("chunk_size", 100, ByteSizeUnit.BYTES)
        );

        createIndexWithRandomDocs("test-idx", 100);

        logger.info("--> snapshot");
        client.admin().cluster().prepareCreateSnapshot("test-repo", "test-snap").setWaitForCompletion(true).setIndices("test-idx").get();

        logger.info("--> delete index");
        cluster().wipeIndices("test-idx");

        logger.info("--> restore index");
        client.admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey(), "100b").build())
            .get();
        ActionFuture<RestoreSnapshotResponse> restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .execute();

        // check if throttling is active
        assertBusy(() -> {
            long restorePause = 0L;
            for (RepositoriesService repositoriesService : internalCluster().getDataNodeInstances(RepositoriesService.class)) {
                restorePause += repositoriesService.repository("test-repo").getRestoreThrottleTimeInNanos();
            }
            assertThat(restorePause, greaterThan(TimeValue.timeValueSeconds(randomIntBetween(1, 5)).nanos()));
            assertFalse(restoreSnapshotResponse.isDone());
        }, 30, TimeUnit.SECONDS);

        // run at full speed again
        client.admin()
            .cluster()
            .prepareUpdateSettings()
            .setTransientSettings(Settings.builder().putNull(INDICES_RECOVERY_MAX_BYTES_PER_SEC_SETTING.getKey()).build())
            .get();

        // check that restore now completes quickly (i.e. within 20 seconds)
        assertThat(restoreSnapshotResponse.get(20L, TimeUnit.SECONDS).getRestoreInfo().totalShards(), greaterThan(0));
        assertDocCount("test-idx", 100L);
    }

    public void testChangeSettingsOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        logger.info("--> create test index with case-preserving search analyzer");

        Settings.Builder indexSettings = Settings.builder()
            .put(indexSettings())
            .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
            .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), "10s")
            .put("index.analysis.analyzer.my_analyzer.type", "custom")
            .put("index.analysis.analyzer.my_analyzer.tokenizer", "standard");

        assertAcked(prepareCreate("test-idx", 2, indexSettings));

        int numberOfShards = getNumShards("test-idx").numPrimaries;
        assertAcked(
            client().admin()
                .indices()
                .preparePutMapping("test-idx")
                .setSource("field1", "type=text,analyzer=standard,search_analyzer=my_analyzer")
        );
        final int numdocs = randomIntBetween(10, 100);
        IndexRequestBuilder[] builders = new IndexRequestBuilder[numdocs];
        for (int i = 0; i < builders.length; i++) {
            builders[i] = client().prepareIndex("test-idx").setId(Integer.toString(i)).setSource("field1", "Foo bar " + i);
        }
        indexRandom(true, builders);
        flushAndRefresh();

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), 0);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);

        logger.info("--> snapshot it");
        CreateSnapshotResponse createSnapshotResponse = client.admin()
            .cluster()
            .prepareCreateSnapshot("test-repo", "test-snap")
            .setWaitForCompletion(true)
            .setIndices("test-idx")
            .get();
        assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
        assertThat(
            createSnapshotResponse.getSnapshotInfo().successfulShards(),
            equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
        );

        logger.info("--> delete the index and recreate it while changing refresh interval and analyzer");
        cluster().wipeIndices("test-idx");

        Settings newIndexSettings = Settings.builder()
            .put("refresh_interval", "5s")
            .put("index.analysis.analyzer.my_analyzer.type", "standard")
            .build();

        Settings newIncorrectIndexSettings = Settings.builder()
            .put(newIndexSettings)
            .put(SETTING_NUMBER_OF_SHARDS, numberOfShards + 100)
            .build();

        logger.info("--> try restoring while changing the number of shards - should fail");
        assertRequestBuilderThrows(
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIgnoreIndexSettings("index.analysis.*")
                .setIndexSettings(newIncorrectIndexSettings)
                .setWaitForCompletion(true),
            SnapshotRestoreException.class
        );

        logger.info("--> try restoring while changing the number of replicas to a negative number - should fail");
        Settings newIncorrectReplicasIndexSettings = Settings.builder()
            .put(newIndexSettings)
            .put(SETTING_NUMBER_OF_REPLICAS.substring(IndexMetadata.INDEX_SETTING_PREFIX.length()), randomIntBetween(-10, -1))
            .build();
        assertRequestBuilderThrows(
            client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIgnoreIndexSettings("index.analysis.*")
                .setIndexSettings(newIncorrectReplicasIndexSettings)
                .setWaitForCompletion(true),
            IllegalArgumentException.class
        );

        logger.info("--> restore index with correct settings from the snapshot");
        RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIgnoreIndexSettings("index.analysis.*")
            .setIndexSettings(newIndexSettings)
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that correct settings are restored");
        GetSettingsResponse getSettingsResponse = client.admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", INDEX_REFRESH_INTERVAL_SETTING.getKey()), equalTo("5s"));
        // Make sure that number of shards didn't change
        assertThat(getSettingsResponse.getSetting("test-idx", SETTING_NUMBER_OF_SHARDS), equalTo("" + numberOfShards));
        assertThat(getSettingsResponse.getSetting("test-idx", "index.analysis.analyzer.my_analyzer.type"), equalTo("standard"));

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);

        logger.info("--> delete the index and recreate it while deleting all index settings");
        cluster().wipeIndices("test-idx");

        logger.info("--> restore index with correct settings from the snapshot");
        restoreSnapshotResponse = client.admin()
            .cluster()
            .prepareRestoreSnapshot("test-repo", "test-snap")
            .setIgnoreIndexSettings("*") // delete everything we can delete
            .setIndexSettings(newIndexSettings)
            .setWaitForCompletion(true)
            .execute()
            .actionGet();
        assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

        logger.info("--> assert that correct settings are restored and index is still functional");
        getSettingsResponse = client.admin().indices().prepareGetSettings("test-idx").execute().actionGet();
        assertThat(getSettingsResponse.getSetting("test-idx", INDEX_REFRESH_INTERVAL_SETTING.getKey()), equalTo("5s"));
        // Make sure that number of shards didn't change
        assertThat(getSettingsResponse.getSetting("test-idx", SETTING_NUMBER_OF_SHARDS), equalTo("" + numberOfShards));

        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "Foo")).get(), numdocs);
        assertHitCount(client.prepareSearch("test-idx").setSize(0).setQuery(matchQuery("field1", "bar")).get(), numdocs);
    }

    public void testRecreateBlocksOnRestore() throws Exception {
        Client client = client();

        createRepository("test-repo", "fs");

        Settings.Builder indexSettings = Settings.builder()
            .put(indexSettings())
            .put(SETTING_NUMBER_OF_REPLICAS, between(0, 1))
            .put(INDEX_REFRESH_INTERVAL_SETTING.getKey(), "10s");

        logger.info("--> create index");
        assertAcked(prepareCreate("test-idx", 2, indexSettings));

        try {
            List<String> initialBlockSettings = randomSubsetOf(
                randomInt(3),
                IndexMetadata.SETTING_BLOCKS_WRITE,
                IndexMetadata.SETTING_BLOCKS_METADATA,
                IndexMetadata.SETTING_READ_ONLY
            );
            Settings.Builder initialSettingsBuilder = Settings.builder();
            for (String blockSetting : initialBlockSettings) {
                initialSettingsBuilder.put(blockSetting, true);
            }
            Settings initialSettings = initialSettingsBuilder.build();
            logger.info("--> using initial block settings {}", initialSettings);

            if (!initialSettings.isEmpty()) {
                logger.info("--> apply initial blocks to index");
                client().admin().indices().prepareUpdateSettings("test-idx").setSettings(initialSettingsBuilder).get();
            }

            logger.info("--> snapshot index");
            CreateSnapshotResponse createSnapshotResponse = client.admin()
                .cluster()
                .prepareCreateSnapshot("test-repo", "test-snap")
                .setWaitForCompletion(true)
                .setIndices("test-idx")
                .get();
            assertThat(createSnapshotResponse.getSnapshotInfo().successfulShards(), greaterThan(0));
            assertThat(
                createSnapshotResponse.getSnapshotInfo().successfulShards(),
                equalTo(createSnapshotResponse.getSnapshotInfo().totalShards())
            );

            logger.info("--> remove blocks and delete index");
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_METADATA);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_READ_ONLY);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_WRITE);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_READ);
            cluster().wipeIndices("test-idx");

            logger.info("--> restore index with additional block changes");
            List<String> changeBlockSettings = randomSubsetOf(
                randomInt(4),
                IndexMetadata.SETTING_BLOCKS_METADATA,
                IndexMetadata.SETTING_BLOCKS_WRITE,
                IndexMetadata.SETTING_READ_ONLY,
                IndexMetadata.SETTING_BLOCKS_READ
            );
            Settings.Builder changedSettingsBuilder = Settings.builder();
            for (String blockSetting : changeBlockSettings) {
                changedSettingsBuilder.put(blockSetting, randomBoolean());
            }
            Settings changedSettings = changedSettingsBuilder.build();
            logger.info("--> applying changed block settings {}", changedSettings);

            RestoreSnapshotResponse restoreSnapshotResponse = client.admin()
                .cluster()
                .prepareRestoreSnapshot("test-repo", "test-snap")
                .setIndexSettings(changedSettings)
                .setWaitForCompletion(true)
                .execute()
                .actionGet();
            assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

            ClusterBlocks blocks = client.admin().cluster().prepareState().clear().setBlocks(true).get().getState().blocks();
            // compute current index settings (as we cannot query them if they contain SETTING_BLOCKS_METADATA)
            Settings mergedSettings = Settings.builder().put(initialSettings).put(changedSettings).build();
            logger.info("--> merged block settings {}", mergedSettings);

            logger.info("--> checking consistency between settings and blocks");
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_METADATA, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_METADATA_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_READ, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_READ_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_BLOCKS_WRITE, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_WRITE_BLOCK))
            );
            assertThat(
                mergedSettings.getAsBoolean(IndexMetadata.SETTING_READ_ONLY, false),
                is(blocks.hasIndexBlock("test-idx", IndexMetadata.INDEX_READ_ONLY_BLOCK))
            );
        } finally {
            logger.info("--> cleaning up blocks");
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_METADATA);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_READ_ONLY);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_WRITE);
            disableIndexBlock("test-idx", IndexMetadata.SETTING_BLOCKS_READ);
        }
    }

    public void testForbidDisableSoftDeletesDuringRestore() throws Exception {
        createRepository("test-repo", "fs");
        final Settings.Builder settings = Settings.builder();
        if (randomBoolean()) {
            settings.put(INDEX_SOFT_DELETES_SETTING.getKey(), true);
        }
        createIndex("test-index", settings.build());
        ensureGreen();
        if (randomBoolean()) {
            indexRandomDocs("test-index", between(0, 100));
            flush("test-index");
        }
        clusterAdmin().prepareCreateSnapshot("test-repo", "snapshot-0").setIndices("test-index").setWaitForCompletion(true).get();
        final SnapshotRestoreException restoreError = expectThrows(
            SnapshotRestoreException.class,
            () -> clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                .setIndexSettings(Settings.builder().put(INDEX_SOFT_DELETES_SETTING.getKey(), false))
                .setRenamePattern("test-index")
                .setRenameReplacement("new-index")
                .get()
        );
        assertThat(restoreError.getMessage(), containsString("cannot disable setting [index.soft_deletes.enabled] on restore"));
    }

    public void testRestoreBalancedReplica() {
        try {
            createRepository("test-repo", "fs");
            DeleteIndexTemplateRequestBuilder deleteTemplate = client().admin().indices().prepareDeleteTemplate("random_index_template");
            assertAcked(deleteTemplate.execute().actionGet());

            createIndex("test-index", Settings.builder().put("index.number_of_replicas", 0).build());
            createIndex(".system-index", Settings.builder().put("index.number_of_replicas", 0).build());
            ensureGreen();
            clusterAdmin().prepareCreateSnapshot("test-repo", "snapshot-0")
                .setIndices("test-index", ".system-index")
                .setWaitForCompletion(true)
                .get();
            manageReplicaSettingForDefaultReplica(true);

            final IllegalArgumentException restoreError = expectThrows(
                IllegalArgumentException.class,
                () -> clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                    .setRenamePattern("test-index")
                    .setRenameReplacement("new-index")
                    .setIndices("test-index")
                    .get()
            );
            assertThat(
                restoreError.getMessage(),
                containsString("expected total copies needs to be a multiple of total awareness attributes [3]")
            );

            final IllegalArgumentException restoreError2 = expectThrows(
                IllegalArgumentException.class,
                () -> clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                    .setRenamePattern("test-index")
                    .setRenameReplacement("new-index-2")
                    .setIndexSettings(
                        Settings.builder().put(SETTING_NUMBER_OF_REPLICAS, 1).put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-1").build()
                    )
                    .setIndices("test-index")
                    .get()
            );
            assertThat(
                restoreError2.getMessage(),
                containsString("expected max cap on auto expand to be a multiple of total awareness attributes [3]")
            );

            RestoreSnapshotResponse restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                .setRenamePattern(".system-index")
                .setRenameReplacement(".system-index-restore-1")
                .setWaitForCompletion(true)
                .setIndices(".system-index")
                .execute()
                .actionGet();

            assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));

            restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                .setRenamePattern("test-index")
                .setRenameReplacement("new-index")
                .setIndexSettings(Settings.builder().put("index.number_of_replicas", 2).build())
                .setWaitForCompletion(true)
                .setIndices("test-index")
                .execute()
                .actionGet();

            restoreSnapshotResponse = clusterAdmin().prepareRestoreSnapshot("test-repo", "snapshot-0")
                .setRenamePattern("test-index")
                .setRenameReplacement("new-index-3")
                .setIndexSettings(
                    Settings.builder().put(SETTING_NUMBER_OF_REPLICAS, 0).put(IndexMetadata.SETTING_AUTO_EXPAND_REPLICAS, "0-2").build()
                )
                .setWaitForCompletion(true)
                .setIndices("test-index")
                .execute()
                .actionGet();

            assertThat(restoreSnapshotResponse.getRestoreInfo().totalShards(), greaterThan(0));
        } finally {
            manageReplicaSettingForDefaultReplica(false);
            randomIndexTemplate();
        }
    }

}
