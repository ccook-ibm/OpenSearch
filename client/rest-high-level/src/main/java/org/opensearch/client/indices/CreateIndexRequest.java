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

package org.opensearch.client.indices;

import org.opensearch.OpenSearchGenerationException;
import org.opensearch.OpenSearchParseException;
import org.opensearch.action.admin.indices.alias.Alias;
import org.opensearch.action.support.ActiveShardCount;
import org.opensearch.client.TimedRequest;
import org.opensearch.client.Validatable;
import org.opensearch.common.Strings;
import org.opensearch.core.common.bytes.BytesArray;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.ParseField;
import org.opensearch.core.xcontent.DeprecationHandler;
import org.opensearch.core.xcontent.MediaType;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.core.xcontent.ToXContentObject;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.core.xcontent.XContentParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.opensearch.common.settings.Settings.Builder.EMPTY_SETTINGS;

/**
 * A request to create an index.
 *
 * @opensearch.api
 */
public class CreateIndexRequest extends TimedRequest implements Validatable, ToXContentObject {
    static final ParseField MAPPINGS = new ParseField("mappings");
    static final ParseField SETTINGS = new ParseField("settings");
    static final ParseField ALIASES = new ParseField("aliases");

    private final String index;
    private Settings settings = EMPTY_SETTINGS;

    private BytesReference mappings;
    private XContentType mappingsXContentType;

    private final Set<Alias> aliases = new HashSet<>();

    private ActiveShardCount waitForActiveShards = ActiveShardCount.DEFAULT;

    /**
     * Constructs a new request to create an index with the specified name.
     */
    public CreateIndexRequest(String index) {
        if (index == null) {
            throw new IllegalArgumentException("The index name cannot be null.");
        }
        this.index = index;
    }

    /**
     * The name of the index to create.
     */
    public String index() {
        return index;
    }

    /**
     * The settings to create the index with.
     */
    public Settings settings() {
        return settings;
    }

    /**
     * The settings to create the index with.
     */
    public CreateIndexRequest settings(Settings.Builder settings) {
        this.settings = settings.build();
        return this;
    }

    /**
     * The settings to create the index with.
     */
    public CreateIndexRequest settings(Settings settings) {
        this.settings = settings;
        return this;
    }

    /**
     * The settings to create the index with (either json or yaml format)
     *
     * @deprecated use {@link #settings(String source, MediaType mediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest settings(String source, XContentType xContentType) {
        this.settings = Settings.builder().loadFromSource(source, xContentType).build();
        return this;
    }

    /**
     * The settings to create the index with (either json or yaml format)
     */
    public CreateIndexRequest settings(String source, MediaType mediaType) {
        this.settings = Settings.builder().loadFromSource(source, mediaType).build();
        return this;
    }

    /**
     * Allows to set the settings using a json builder.
     */
    public CreateIndexRequest settings(XContentBuilder builder) {
        settings(Strings.toString(builder), builder.contentType());
        return this;
    }

    /**
     * The settings to create the index with (either json/yaml/properties format)
     */
    public CreateIndexRequest settings(Map<String, ?> source) {
        this.settings = Settings.builder().loadFromMap(source).build();
        return this;
    }

    public BytesReference mappings() {
        return mappings;
    }

    public XContentType mappingsXContentType() {
        return mappingsXContentType;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     * @param xContentType The content type of the source
     *
     * @deprecated use {@link #mapping(String source, MediaType mediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest mapping(String source, XContentType xContentType) {
        return mapping(new BytesArray(source), xContentType);
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     * @param mediaType The media type of the source
     */
    public CreateIndexRequest mapping(String source, MediaType mediaType) {
        return mapping(new BytesArray(source), mediaType);
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     */
    public CreateIndexRequest mapping(XContentBuilder source) {
        return mapping(BytesReference.bytes(source), source.contentType());
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     */
    public CreateIndexRequest mapping(Map<String, ?> source) {
        try {
            XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
            builder.map(source);
            return mapping(BytesReference.bytes(builder), builder.contentType());
        } catch (IOException e) {
            throw new OpenSearchGenerationException("Failed to generate [" + source + "]", e);
        }
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     * @param xContentType the content type of the mapping source
     *
     * @deprecated use {@link #mapping(BytesReference source, MediaType mediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest mapping(BytesReference source, XContentType xContentType) {
        Objects.requireNonNull(xContentType);
        mappings = source;
        mappingsXContentType = xContentType;
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * Note that the definition should *not* be nested under a type name.
     *
     * @param source The mapping source
     * @param mediaType the content type of the mapping source
     */
    public CreateIndexRequest mapping(BytesReference source, MediaType mediaType) {
        Objects.requireNonNull(mediaType);
        mappings = source;
        mappingsXContentType = XContentType.fromMediaType(mediaType);
        return this;
    }

    public Set<Alias> aliases() {
        return this.aliases;
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequest aliases(Map<String, ?> source) {
        try {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.map(source);
            return aliases(BytesReference.bytes(builder), builder.contentType());
        } catch (IOException e) {
            throw new OpenSearchGenerationException("Failed to generate [" + source + "]", e);
        }
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequest aliases(XContentBuilder source) {
        return aliases(BytesReference.bytes(source), source.contentType());
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     *
     * @deprecated use {@link #aliases(String, MediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest aliases(String source, XContentType contentType) {
        return aliases(new BytesArray(source), contentType);
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequest aliases(String source, MediaType mediaType) {
        return aliases(new BytesArray(source), mediaType);
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     *
     * @deprecated use {@link #aliases(BytesReference source, MediaType contentType)} instead
     */
    @Deprecated
    public CreateIndexRequest aliases(BytesReference source, XContentType contentType) {
        return aliases(source, (MediaType) contentType);
    }

    /**
     * Sets the aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequest aliases(BytesReference source, MediaType contentType) {
        // EMPTY is safe here because we never call namedObject
        try (
            XContentParser parser = XContentHelper.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                source,
                contentType
            )
        ) {
            // move to the first alias
            parser.nextToken();
            while ((parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                alias(Alias.fromXContent(parser));
            }
            return this;
        } catch (IOException e) {
            throw new OpenSearchParseException("Failed to parse aliases", e);
        }
    }

    /**
     * Adds an alias that will be associated with the index when it gets created
     */
    public CreateIndexRequest alias(Alias alias) {
        this.aliases.add(alias);
        return this;
    }

    /**
     * Adds aliases that will be associated with the index when it gets created
     */
    public CreateIndexRequest aliases(Collection<Alias> aliases) {
        this.aliases.addAll(aliases);
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     *
     * @deprecated use {@link #source(String, MediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest source(String source, XContentType xContentType) {
        return source(new BytesArray(source), xContentType);
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     */
    public CreateIndexRequest source(String source, MediaType mediaType) {
        return source(new BytesArray(source), mediaType);
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     */
    public CreateIndexRequest source(XContentBuilder source) {
        return source(BytesReference.bytes(source), source.contentType());
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     *
     * @deprecated use {@link #source(BytesReference, MediaType)} instead
     */
    @Deprecated
    public CreateIndexRequest source(BytesReference source, XContentType xContentType) {
        Objects.requireNonNull(xContentType);
        source(XContentHelper.convertToMap(source, false, xContentType).v2());
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     */
    public CreateIndexRequest source(BytesReference source, MediaType mediaType) {
        Objects.requireNonNull(mediaType);
        source(XContentHelper.convertToMap(source, false, mediaType).v2());
        return this;
    }

    /**
     * Sets the settings and mappings as a single source.
     *
     * Note that the mapping definition should *not* be nested under a type name.
     */
    @SuppressWarnings("unchecked")
    public CreateIndexRequest source(Map<String, ?> source) {
        DeprecationHandler deprecationHandler = DeprecationHandler.THROW_UNSUPPORTED_OPERATION;
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String name = entry.getKey();
            if (SETTINGS.match(name, deprecationHandler)) {
                settings((Map<String, Object>) entry.getValue());
            } else if (MAPPINGS.match(name, deprecationHandler)) {
                mapping((Map<String, Object>) entry.getValue());
            } else if (ALIASES.match(name, deprecationHandler)) {
                aliases((Map<String, Object>) entry.getValue());
            }
        }
        return this;
    }

    public ActiveShardCount waitForActiveShards() {
        return waitForActiveShards;
    }

    /**
     * Sets the number of shard copies that should be active for index creation to return.
     * Defaults to {@link ActiveShardCount#DEFAULT}, which will wait for one shard copy
     * (the primary) to become active. Set this value to {@link ActiveShardCount#ALL} to
     * wait for all shards (primary and all replicas) to be active before returning.
     * Otherwise, use {@link ActiveShardCount#from(int)} to set this value to any
     * non-negative integer, up to the number of copies per shard (number of replicas + 1),
     * to wait for the desired amount of shard copies to become active before returning.
     * Index creation will only wait up until the timeout value for the number of shard copies
     * to be active before returning.  Check {@link CreateIndexResponse#isShardsAcknowledged()} to
     * determine if the requisite shard copies were all started before returning or timing out.
     *
     * @param waitForActiveShards number of active shard copies to wait on
     */
    public CreateIndexRequest waitForActiveShards(ActiveShardCount waitForActiveShards) {
        this.waitForActiveShards = waitForActiveShards;
        return this;
    }

    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        innerToXContent(builder, params);
        builder.endObject();
        return builder;
    }

    public XContentBuilder innerToXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(SETTINGS.getPreferredName());
        settings.toXContent(builder, params);
        builder.endObject();

        if (mappings != null) {
            try (InputStream stream = mappings.streamInput()) {
                builder.rawField(MAPPINGS.getPreferredName(), stream, mappingsXContentType);
            }
        }

        builder.startObject(ALIASES.getPreferredName());
        for (Alias alias : aliases) {
            alias.toXContent(builder, params);
        }
        builder.endObject();
        return builder;
    }
}
