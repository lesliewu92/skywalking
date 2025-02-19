/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.storage.plugin.elasticsearch.base;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.skywalking.library.elasticsearch.response.Index;
import org.apache.skywalking.library.elasticsearch.response.IndexTemplate;
import org.apache.skywalking.library.elasticsearch.response.Mappings;
import org.apache.skywalking.oap.server.core.storage.StorageException;
import org.apache.skywalking.oap.server.core.storage.model.Model;
import org.apache.skywalking.oap.server.core.storage.model.ModelColumn;
import org.apache.skywalking.oap.server.core.storage.model.ModelInstaller;
import org.apache.skywalking.oap.server.library.client.Client;
import org.apache.skywalking.oap.server.library.client.elasticsearch.ElasticSearchClient;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.util.StringUtil;
import org.apache.skywalking.oap.server.storage.plugin.elasticsearch.StorageModuleElasticsearchConfig;

@Slf4j
public class StorageEsInstaller extends ModelInstaller {
    private final Gson gson = new Gson();
    private final StorageModuleElasticsearchConfig config;
    protected final ColumnTypeEsMapping columnTypeEsMapping;

    /**
     * The mappings of the template .
     */
    private final IndexStructures structures;

    public StorageEsInstaller(Client client,
                              ModuleManager moduleManager,
                              StorageModuleElasticsearchConfig config) {
        super(client, moduleManager);
        this.columnTypeEsMapping = new ColumnTypeEsMapping();
        this.config = config;
        this.structures = getStructures();
    }

    protected IndexStructures getStructures() {
        return new IndexStructures();
    }

    @Override
    protected boolean isExists(Model model) {
        ElasticSearchClient esClient = (ElasticSearchClient) client;
        String tableName = IndexController.INSTANCE.getTableName(model);
        IndexController.LogicIndicesRegister.registerRelation(model.getName(), tableName);
        if (!model.isTimeSeries()) {
            boolean exist = esClient.isExistsIndex(tableName);
            if (exist) {
                Mappings historyMapping = esClient.getIndex(tableName)
                                                  .map(Index::getMappings)
                                                  .orElseGet(Mappings::new);
                structures.putStructure(tableName, historyMapping);
                exist = structures.containsStructure(tableName, createMapping(model));
            }
            return exist;
        }
        boolean templateExists = esClient.isExistsTemplate(tableName);
        final Optional<IndexTemplate> template = esClient.getTemplate(tableName);
        boolean lastIndexExists = esClient.isExistsIndex(TimeSeriesUtils.latestWriteIndexName(model));

        if ((templateExists && !template.isPresent()) || (!templateExists && template.isPresent())) {
            throw new Error("[Bug warning] ElasticSearch client query template result is not consistent. " +
                                "Please file an issue to Apache SkyWalking.(https://github.com/apache/skywalking/issues)");
        }

        boolean exist = templateExists && lastIndexExists;

        if (exist) {
            structures.putStructure(
                tableName, template.get().getMappings()
            );
            exist = structures.containsStructure(tableName, createMapping(model));
        }
        return exist;
    }

    @Override
    protected void createTable(Model model) throws StorageException {
        if (model.isTimeSeries()) {
            createTimeSeriesTable(model);
        } else {
            createNormalTable(model);
        }
    }

    private void createNormalTable(Model model) throws StorageException {
        ElasticSearchClient esClient = (ElasticSearchClient) client;
        String tableName = IndexController.INSTANCE.getTableName(model);
        Mappings mapping = createMapping(model);
        if (!esClient.isExistsIndex(tableName)) {
            Map<String, Object> settings = createSetting(model);
            boolean isAcknowledged = esClient.createIndex(tableName, mapping, settings);
            log.info("create {} index finished, isAcknowledged: {}", tableName, isAcknowledged);
            if (!isAcknowledged) {
                throw new StorageException("create " + tableName + " index failure, ");
            }
        } else {
            Mappings historyMapping = esClient.getIndex(tableName)
                                              .map(Index::getMappings)
                                              .orElseGet(Mappings::new);
            structures.putStructure(tableName, mapping);
            Mappings appendMapping = structures.diffStructure(tableName, historyMapping);
            if (appendMapping.getProperties() != null && !appendMapping.getProperties().isEmpty()) {
                boolean isAcknowledged = esClient.updateIndexMapping(tableName, appendMapping);
                log.info("update {} index finished, isAcknowledged: {}, append mappings: {}", tableName,
                         isAcknowledged, appendMapping
                );
                if (!isAcknowledged) {
                    throw new StorageException("update " + tableName + " index failure");
                }
            }
        }
    }

    private void createTimeSeriesTable(Model model) throws StorageException {
        ElasticSearchClient esClient = (ElasticSearchClient) client;
        String tableName = IndexController.INSTANCE.getTableName(model);
        Map<String, Object> settings = createSetting(model);
        Mappings mapping = createMapping(model);
        String indexName = TimeSeriesUtils.latestWriteIndexName(model);
        try {
            boolean shouldUpdateTemplate = !esClient.isExistsTemplate(tableName);
            shouldUpdateTemplate = shouldUpdateTemplate || !structures.containsStructure(tableName, mapping);
            if (shouldUpdateTemplate) {
                structures.putStructure(tableName, mapping);
                boolean isAcknowledged = esClient.createOrUpdateTemplate(
                    tableName, settings, structures.getMapping(tableName), config.getIndexTemplateOrder());
                log.info("create {} index template finished, isAcknowledged: {}", tableName, isAcknowledged);
                if (!isAcknowledged) {
                    throw new IOException("create " + tableName + " index template failure, ");
                }

                if (esClient.isExistsIndex(indexName)) {
                    Mappings historyMapping = esClient.getIndex(indexName)
                                                      .map(Index::getMappings)
                                                      .orElseGet(Mappings::new);
                    Mappings appendMapping = structures.diffStructure(tableName, historyMapping);
                    if (appendMapping.getProperties() != null && !appendMapping.getProperties().isEmpty()) {
                        isAcknowledged = esClient.updateIndexMapping(indexName, appendMapping);
                        log.info("update {} index finished, isAcknowledged: {}, append mappings: {}", indexName,
                                 isAcknowledged, appendMapping
                        );
                        if (!isAcknowledged) {
                            throw new StorageException("update " + indexName + " time series index failure");
                        }
                    }
                } else {
                    isAcknowledged = esClient.createIndex(indexName);
                    log.info("create {} index finished, isAcknowledged: {}", indexName, isAcknowledged);
                    if (!isAcknowledged) {
                        throw new StorageException("create " + indexName + " time series index failure");
                    }
                }
            }
        } catch (IOException e) {
            throw new StorageException("cannot create " + tableName + " index template", e);
        }
    }

    protected Map<String, Object> createSetting(Model model) throws StorageException {
        Map<String, Object> setting = new HashMap<>();

        setting.put("index.number_of_replicas", model.isSuperDataset()
            ? config.getSuperDatasetIndexReplicasNumber()
            : config.getIndexReplicasNumber());
        setting.put("index.number_of_shards", model.isSuperDataset()
            ? config.getIndexShardsNumber() * config.getSuperDatasetIndexShardsFactor()
            : config.getIndexShardsNumber());
        // Set the index refresh period as INT(flushInterval * 2/3). At the edge case,
        // in low traffic(traffic < bulkActions in the whole period), there is a possible case, 2 period bulks are included in
        // one index refresh rebuild operation, which could cause version conflicts. And this case can't be fixed
        // through `core/persistentPeriod` as the bulk fresh is not controlled by the persistent timer anymore.
        int indexRefreshInterval = config.getFlushInterval() * 2 / 3;
        if (indexRefreshInterval < 5) {
            // The refresh interval should not be less than 5 seconds (the recommended default value = 10s),
            // and the bulk flush interval should not be set less than 8s (the recommended default value = 15s).
            // This is a precaution case which makes ElasticSearch server has reasonable refresh interval,
            // even this value is set too small by end user manually.
            indexRefreshInterval = 5;
        }
        setting.put("index.refresh_interval", indexRefreshInterval + "s");
        setting.put("analysis", getAnalyzerSetting(model.getColumns()));
        if (!StringUtil.isEmpty(config.getAdvanced())) {
            Map<String, Object> advancedSettings = gson.fromJson(config.getAdvanced(), Map.class);
            setting.putAll(advancedSettings);
        }
        return setting;
    }

    private Map getAnalyzerSetting(List<ModelColumn> analyzerTypes) throws StorageException {
        AnalyzerSetting analyzerSetting = new AnalyzerSetting();
        for (final ModelColumn column : analyzerTypes) {
            if (!column.getElasticSearchExtension().needMatchQuery()) {
                continue;
            }
            AnalyzerSetting setting = AnalyzerSetting.Generator.getGenerator(
                                                         column.getElasticSearchExtension().getAnalyzer())
                                                               .getGenerateFunc()
                                                               .generate(config);
            analyzerSetting.combine(setting);
        }
        return gson.fromJson(gson.toJson(analyzerSetting), Map.class);
    }

    protected Mappings createMapping(Model model) {
        Map<String, Object> properties = new HashMap<>();
        Mappings.Source source = new Mappings.Source();
        for (ModelColumn columnDefine : model.getColumns()) {
            final String type = columnTypeEsMapping.transform(columnDefine.getType(), columnDefine.getGenericType());
            if (columnDefine.getElasticSearchExtension().needMatchQuery()) {
                String matchCName = MatchCNameBuilder.INSTANCE.build(columnDefine.getColumnName().getName());

                Map<String, Object> originalColumn = new HashMap<>();
                originalColumn.put("type", type);
                originalColumn.put("copy_to", matchCName);
                properties.put(columnDefine.getColumnName().getName(), originalColumn);

                Map<String, Object> matchColumn = new HashMap<>();
                matchColumn.put("type", "text");
                matchColumn.put("analyzer", columnDefine.getElasticSearchExtension().getAnalyzer().getName());
                properties.put(matchCName, matchColumn);
            } else {
                Map<String, Object> column = new HashMap<>();
                column.put("type", type);
                // no index parameter is allowed for binary type, since ES 8.0
                if (columnDefine.isStorageOnly() && !"binary".equals(type)) {
                    column.put("index", false);
                }
                properties.put(columnDefine.getColumnName().getName(), column);
            }

            if (columnDefine.isIndexOnly()) {
                source.getExcludes().add(columnDefine.getColumnName().getName());
            }
        }

        if (IndexController.INSTANCE.isMetricModel(model)) {
            Map<String, Object> column = new HashMap<>();
            column.put("type", "keyword");
            properties.put(IndexController.LogicIndicesRegister.METRIC_TABLE_NAME, column);
        }
        Mappings mappings = Mappings.builder()
                                    .type("type")
                                    .properties(properties)
                                    .source(source)
                                    .build();
        log.debug("elasticsearch index template setting: {}", mappings.toString());

        return mappings;
    }
}
