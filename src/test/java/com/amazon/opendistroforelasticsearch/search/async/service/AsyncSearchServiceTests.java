/*
 *   Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.search.async.service;

import com.amazon.opendistroforelasticsearch.commons.authuser.User;
import com.amazon.opendistroforelasticsearch.search.async.AsyncSearchSingleNodeTestCase;
import com.amazon.opendistroforelasticsearch.search.async.context.AsyncSearchContext;
import com.amazon.opendistroforelasticsearch.search.async.context.active.AsyncSearchActiveContext;
import com.amazon.opendistroforelasticsearch.search.async.context.persistence.AsyncSearchPersistenceContext;
import com.amazon.opendistroforelasticsearch.search.async.listener.AsyncSearchProgressListener;
import com.amazon.opendistroforelasticsearch.search.async.request.SubmitAsyncSearchRequest;
import com.amazon.opendistroforelasticsearch.search.async.task.AsyncSearchTask;
import com.amazon.opendistroforelasticsearch.search.async.utils.TestClientUtils;
import org.apache.lucene.search.TotalHits;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchAction;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.search.profile.SearchProfileShardResults;
import org.elasticsearch.search.suggest.Suggest;
import org.elasticsearch.tasks.TaskId;
import org.junit.After;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;

import static com.amazon.opendistroforelasticsearch.search.async.context.state.AsyncSearchState.INIT;
import static com.amazon.opendistroforelasticsearch.search.async.context.state.AsyncSearchState.RUNNING;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.action.ActionListener.wrap;
import static org.elasticsearch.common.unit.TimeValue.timeValueDays;
import static org.hamcrest.Matchers.greaterThan;

public class AsyncSearchServiceTests extends AsyncSearchSingleNodeTestCase {

    public void testFindContext() throws InterruptedException {
        //create context
        AsyncSearchService asyncSearchService = getInstanceFromNode(AsyncSearchService.class);
        TimeValue keepAlive = timeValueDays(9);
        boolean keepOnCompletion = randomBoolean();
        User user1 = TestClientUtils.randomUser();
        User user2 = TestClientUtils.randomUser();
        SearchRequest searchRequest = new SearchRequest();
        SubmitAsyncSearchRequest submitAsyncSearchRequest = new SubmitAsyncSearchRequest(searchRequest);
        submitAsyncSearchRequest.keepOnCompletion(keepOnCompletion);
        submitAsyncSearchRequest.keepAlive(keepAlive);
        AsyncSearchContext context = asyncSearchService.createAndStoreContext(submitAsyncSearchRequest,
                System.currentTimeMillis(), () -> getInstanceFromNode(SearchService.class).aggReduceContextBuilder(searchRequest), user1);
        assertTrue(context instanceof AsyncSearchActiveContext);
        AsyncSearchActiveContext asyncSearchActiveContext = (AsyncSearchActiveContext) context;
        assertNull(asyncSearchActiveContext.getTask());
        assertNull(asyncSearchActiveContext.getAsyncSearchId());
        assertEquals(asyncSearchActiveContext.getAsyncSearchState(), INIT);
        assertEquals(asyncSearchActiveContext.getUser(), user1);
        //bootstrap search
        AsyncSearchTask task = new AsyncSearchTask(randomNonNegativeLong(), "transport", SearchAction.NAME, TaskId.EMPTY_TASK_ID,
                emptyMap(), (AsyncSearchActiveContext) context, null, (c) -> {
        });
        asyncSearchService.bootstrapSearch(task, context.getContextId());
        assertEquals(asyncSearchActiveContext.getTask(), task);
        assertEquals(asyncSearchActiveContext.getStartTimeMillis(), task.getStartTime());
        assertEquals(asyncSearchActiveContext.getExpirationTimeMillis(), task.getStartTime() + keepAlive.millis());
        assertEquals(asyncSearchActiveContext.getAsyncSearchState(), RUNNING);
        CountDownLatch findContextLatch = new CountDownLatch(3);
        ActionListener<AsyncSearchContext> expectedSuccessfulActive = wrap(
                r -> {
                    try {
                        assertTrue(r instanceof AsyncSearchActiveContext);
                        assertEquals(r, context);
                    } finally {
                        findContextLatch.countDown();
                    }
                }, e -> {
                    try {
                        logger.error(e);
                        fail("Find context shouldn't have failed");
                    } finally {
                        findContextLatch.countDown();
                    }
                }
        );
        ActionListener<AsyncSearchContext> expectedSecurityException = wrap(
                r -> {
                    try {
                        fail("Expecting security exception");
                    } finally {
                        findContextLatch.countDown();
                    }
                }, e -> {
                    try {
                        assertTrue(e instanceof ElasticsearchSecurityException);
                    } finally {
                        findContextLatch.countDown();
                    }
                }
        );
        asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                asyncSearchActiveContext.getContextId(), user1, expectedSuccessfulActive);
        asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                asyncSearchActiveContext.getContextId(), user2, expectedSecurityException);
        asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                asyncSearchActiveContext.getContextId(), null, expectedSuccessfulActive);

        findContextLatch.await();

        AsyncSearchProgressListener asyncSearchProgressListener = asyncSearchActiveContext.getAsyncSearchProgressListener();
        boolean success = randomBoolean();
        if (success) { //successful search response
            asyncSearchProgressListener.onResponse(getMockSearchResponse());

        } else { // exception occurred in search
            asyncSearchProgressListener.onFailure(new RuntimeException("test"));
        }
        if (keepOnCompletion) { //persist to disk
            TestClientUtils.assertResponsePersistence(client(), context.getAsyncSearchId());
            CountDownLatch findContextLatch1 = new CountDownLatch(3);
            ActionListener<AsyncSearchContext> expectedSuccessfulPersisted = wrap(
                    r -> {
                        try {
                            assertNotEquals(r, asyncSearchActiveContext);
                            assertTrue(r instanceof AsyncSearchPersistenceContext);
                        } finally {
                            findContextLatch1.countDown();
                        }
                    }, e -> {
                        try {
                            fail("Find context shouldn't have failed");
                        } finally {
                            findContextLatch1.countDown();
                        }
                    }
            );
            ActionListener<AsyncSearchContext> expectedSecurityExceptionPersisted = wrap(
                    r -> {
                        try {
                            fail("Expecting security exception");
                        } finally {
                            findContextLatch1.countDown();
                        }
                    }, e -> {
                        try {
                            assertTrue(e instanceof ElasticsearchSecurityException);
                        } finally {
                            findContextLatch1.countDown();
                        }
                    }
            );
            asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                    asyncSearchActiveContext.getContextId(), user1, expectedSuccessfulPersisted);
            asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                    asyncSearchActiveContext.getContextId(), user2, expectedSecurityExceptionPersisted);
            asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(),
                    asyncSearchActiveContext.getContextId(), null, expectedSuccessfulPersisted);

            findContextLatch1.await();
            CountDownLatch freeContextLatch = new CountDownLatch(2);
            asyncSearchService.freeContext(context.getAsyncSearchId(), context.getContextId(), user2, wrap(
                    r -> {
                        try {
                            fail("Expecting security exception");
                        } finally {
                            freeContextLatch.countDown();
                        }
                    }, e -> {
                        try {
                            assertTrue(e instanceof ElasticsearchSecurityException);
                        } finally {
                            freeContextLatch.countDown();
                        }
                    }
            ));
            asyncSearchService.freeContext(context.getAsyncSearchId(), context.getContextId(), user1, wrap(
                    r -> {
                        try {
                            assertTrue("persistence context should be deleted", r);
                        } finally {
                            freeContextLatch.countDown();
                        }
                    },
                    e -> {
                        try {
                            fail("persistence context should be deleted");
                        } finally {
                            freeContextLatch.countDown();
                        }
                    }
            ));
            freeContextLatch.await();
        } else {

            waitUntil(() -> asyncSearchService.getAllActiveContexts().isEmpty());
            CountDownLatch freeContextLatch = new CountDownLatch(1);
            asyncSearchService.freeContext(context.getAsyncSearchId(), context.getContextId(), null, wrap(
                    r -> {
                        try {
                            fail("No context should have been deleted");
                        } finally {
                            freeContextLatch.countDown();
                        }
                    },
                    e -> {
                        try {
                            assertTrue(e instanceof ResourceNotFoundException);
                        } finally {
                            freeContextLatch.countDown();
                        }
                    }
            ));
            freeContextLatch.await();
        }

    }

    public void testUpdateExpirationOnRunningSearch() throws InterruptedException {
        AsyncSearchService asyncSearchService = getInstanceFromNode(AsyncSearchService.class);
        TimeValue keepAlive = timeValueDays(9);
        boolean keepOnCompletion = false;
        SearchRequest searchRequest = new SearchRequest();
        SubmitAsyncSearchRequest submitAsyncSearchRequest = new SubmitAsyncSearchRequest(searchRequest);
        submitAsyncSearchRequest.keepOnCompletion(keepOnCompletion);
        submitAsyncSearchRequest.keepAlive(keepAlive);
        AsyncSearchContext context = asyncSearchService.createAndStoreContext(submitAsyncSearchRequest, System.currentTimeMillis(),
                () -> getInstanceFromNode(SearchService.class).aggReduceContextBuilder(searchRequest), null);
        assertTrue(context instanceof AsyncSearchActiveContext);
        AsyncSearchActiveContext asyncSearchActiveContext = (AsyncSearchActiveContext) context;
        assertNull(asyncSearchActiveContext.getTask());
        assertNull(asyncSearchActiveContext.getAsyncSearchId());
        assertEquals(asyncSearchActiveContext.getAsyncSearchState(), INIT);
        //bootstrap search
        AsyncSearchTask task = new AsyncSearchTask(randomNonNegativeLong(), "transport", SearchAction.NAME, TaskId.EMPTY_TASK_ID,
                emptyMap(), (AsyncSearchActiveContext) context, null, (c) -> {
        });

        asyncSearchService.bootstrapSearch(task, context.getContextId());
        assertEquals(asyncSearchActiveContext.getTask(), task);
        assertEquals(asyncSearchActiveContext.getStartTimeMillis(), task.getStartTime());
        long originalExpirationTimeMillis = asyncSearchActiveContext.getExpirationTimeMillis();
        assertEquals(originalExpirationTimeMillis, task.getStartTime() + keepAlive.millis());
        assertEquals(asyncSearchActiveContext.getAsyncSearchState(), RUNNING);
        CountDownLatch findContextLatch = new CountDownLatch(1);
        asyncSearchService.findContext(asyncSearchActiveContext.getAsyncSearchId(), asyncSearchActiveContext.getContextId(), null, wrap(
                r -> {
                    try {
                        assertTrue(r instanceof AsyncSearchActiveContext);
                        assertEquals(r, context);
                    } finally {
                        findContextLatch.countDown();
                    }
                }, e -> {
                    try {
                        logger.error(e);
                        fail("Find context shouldn't have failed");
                    } finally {
                        findContextLatch.countDown();
                    }
                }
        ));
        findContextLatch.await();
        CountDownLatch updateLatch = new CountDownLatch(1);
        TimeValue newKeepAlive = timeValueDays(10);
        asyncSearchService.updateKeepAliveAndGetContext(asyncSearchActiveContext.getAsyncSearchId(), newKeepAlive,
                asyncSearchActiveContext.getContextId(), null, wrap(r -> {
                    try {
                        assertTrue(r instanceof AsyncSearchActiveContext);
                        assertThat(r.getExpirationTimeMillis(), greaterThan(originalExpirationTimeMillis));
                    } finally {
                        updateLatch.countDown();
                    }
                }, e -> {
                    try {
                        fail();
                    } finally {
                        updateLatch.countDown();
                    }
                }));
        updateLatch.await();

    }

    public void testUpdateExpirationOnPersistedSearch() throws InterruptedException {
        AsyncSearchService asyncSearchService = getInstanceFromNode(AsyncSearchService.class);
        TimeValue keepAlive = timeValueDays(9);
        User user1 = TestClientUtils.randomUser();
        User user2 = TestClientUtils.randomUser();
        SearchRequest searchRequest = new SearchRequest();
        SubmitAsyncSearchRequest submitAsyncSearchRequest = new SubmitAsyncSearchRequest(searchRequest);
        submitAsyncSearchRequest.keepAlive(keepAlive);
        submitAsyncSearchRequest.keepOnCompletion(true);
        AsyncSearchActiveContext context = (AsyncSearchActiveContext) asyncSearchService.createAndStoreContext(submitAsyncSearchRequest,
                System.currentTimeMillis(), () -> getInstanceFromNode(SearchService.class).aggReduceContextBuilder(searchRequest), user1);
        AsyncSearchTask task = new AsyncSearchTask(randomNonNegativeLong(), "transport", SearchAction.NAME, TaskId.EMPTY_TASK_ID,
                emptyMap(), context, null, (c) -> {
        });

        asyncSearchService.bootstrapSearch(task, context.getContextId());
        assertEquals(context.getTask(), task);
        assertEquals(context.getStartTimeMillis(), task.getStartTime());
        long originalExpirationTimeMillis = context.getExpirationTimeMillis();
        assertEquals(originalExpirationTimeMillis, task.getStartTime() + keepAlive.millis());
        assertEquals(context.getAsyncSearchState(), RUNNING);
        context.getAsyncSearchProgressListener().onResponse(getMockSearchResponse());
        TestClientUtils.assertResponsePersistence(client(), context.getAsyncSearchId());
        CountDownLatch updateLatch = new CountDownLatch(2);
        asyncSearchService.updateKeepAliveAndGetContext(context.getAsyncSearchId(), keepAlive,
                context.getContextId(), user2, wrap(r -> {
                    try {
                        fail("Expected security exception");
                    } finally {
                        updateLatch.countDown();
                    }
                }, e -> {
                    try {
                        assertTrue(e instanceof ElasticsearchSecurityException);
                    } finally {
                        updateLatch.countDown();
                    }
                }));
        asyncSearchService.updateKeepAliveAndGetContext(context.getAsyncSearchId(), keepAlive,
                context.getContextId(), user1, wrap(r -> {
                    try {
                        assertTrue(r instanceof AsyncSearchPersistenceContext);
                        assertThat(r.getExpirationTimeMillis(), greaterThan(originalExpirationTimeMillis));
                    } finally {
                        updateLatch.countDown();
                    }
                }, e -> {
                    try {
                        fail();
                    } finally {
                        updateLatch.countDown();
                    }
                }));
        updateLatch.await();

    }


    @After
    public void deleteAsyncSearchIndex() throws InterruptedException {
        CountDownLatch deleteLatch = new CountDownLatch(1);
        client().admin().indices().prepareDelete(INDEX).execute(ActionListener.wrap(r -> deleteLatch.countDown(), e -> {
            deleteLatch.countDown();
        }));
        deleteLatch.await();
    }

    protected SearchResponse getMockSearchResponse() {
        int totalShards = randomInt(100);
        int successfulShards = totalShards - randomInt(100);
        return new SearchResponse(new InternalSearchResponse(
                new SearchHits(new SearchHit[0], new TotalHits(0L, TotalHits.Relation.EQUAL_TO), 0.0f),
                InternalAggregations.from(Collections.emptyList()),
                new Suggest(Collections.emptyList()),
                new SearchProfileShardResults(Collections.emptyMap()), false, false, randomInt(5)),
                "", totalShards, successfulShards, 0, randomNonNegativeLong(),
                ShardSearchFailure.EMPTY_ARRAY, SearchResponse.Clusters.EMPTY);
    }
}