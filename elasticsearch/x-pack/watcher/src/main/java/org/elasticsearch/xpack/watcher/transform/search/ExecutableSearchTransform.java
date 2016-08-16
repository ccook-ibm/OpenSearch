/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.transform.search;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.WatcherScript;
import org.elasticsearch.xpack.watcher.support.init.proxy.WatcherClientProxy;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateService;
import org.elasticsearch.xpack.watcher.transform.ExecutableTransform;
import org.elasticsearch.xpack.watcher.watch.Payload;

public class ExecutableSearchTransform extends ExecutableTransform<SearchTransform, SearchTransform.Result> {

    public static final SearchType DEFAULT_SEARCH_TYPE = SearchType.QUERY_THEN_FETCH;

    protected final WatcherClientProxy client;
    private final WatcherSearchTemplateService searchTemplateService;
    @Nullable protected final TimeValue timeout;

    public ExecutableSearchTransform(SearchTransform transform, ESLogger logger, WatcherClientProxy client,
                                     WatcherSearchTemplateService searchTemplateService, @Nullable TimeValue defaultTimeout) {
        super(transform, logger);
        this.client = client;
        this.searchTemplateService = searchTemplateService;
        this.timeout = transform.getTimeout() != null ? transform.getTimeout() : defaultTimeout;
    }

    @Override
    public SearchTransform.Result execute(WatchExecutionContext ctx, Payload payload) {
        WatcherSearchTemplateRequest request = null;
        try {
            WatcherScript template = transform.getRequest().getOrCreateTemplate();
            BytesReference renderedTemplate = searchTemplateService.renderTemplate(template, ctx, payload);
            // We need to make a copy, so that we don't modify the original instance that we keep around in a watch:
            request = new WatcherSearchTemplateRequest(transform.getRequest(), renderedTemplate);
            SearchResponse resp = client.search(searchTemplateService.toSearchRequest(request), timeout);
            return new SearchTransform.Result(request, new Payload.XContent(resp));
        } catch (Exception e) {
            logger.error("failed to execute [{}] transform for [{}]", e, SearchTransform.TYPE, ctx.id());
            return new SearchTransform.Result(request, e);
        }
    }
}
