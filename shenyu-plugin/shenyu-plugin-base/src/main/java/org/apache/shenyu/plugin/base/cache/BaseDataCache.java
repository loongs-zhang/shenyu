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
 */

package org.apache.shenyu.plugin.base.cache;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.shenyu.common.cache.MemorySafeLRUMap;
import org.apache.shenyu.common.constant.Constants;
import org.apache.shenyu.common.dto.PluginData;
import org.apache.shenyu.common.dto.RuleData;
import org.apache.shenyu.common.dto.SelectorData;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * The type Base data cache.
 */
public final class BaseDataCache {

    private static final BaseDataCache INSTANCE = new BaseDataCache();

    /**
     * pluginName -> PluginData.
     */
    private static final ConcurrentMap<String, PluginData> PLUGIN_MAP = Maps.newConcurrentMap();

    /**
     * pluginName -> SelectorData.
     */
    private static final ConcurrentMap<String, List<SelectorData>> SELECTOR_MAP = Maps.newConcurrentMap();

    /**
     * selectorId -> pluginName.
     */
    private static final ConcurrentMap<String, String> SELECTOR_PLUGIN_MAP = Maps.newConcurrentMap();

    /**
     * conditionId -> SelectorData or RuleData.
     */
    private static final ConcurrentMap<String, Object> CONDITION_MAP = Maps.newConcurrentMap();

    /**
     * pluginName -> conditionId_realDataString -> SelectorData or RuleData.
     *
     * @see org.apache.shenyu.plugin.base.utils.CacheKeyUtils#getKey(org.apache.shenyu.common.dto.ConditionData, java.lang.String)
     */
    private static final MemorySafeLRUMap<String, MemorySafeLRUMap<String, Object>> MATCH_CACHE = new MemorySafeLRUMap<>(Constants.THE_256_MB, 1 << 16);

    /**
     * pluginName -> SelectorData or RuleData -> conditionId_realDataString.
     * When the selector/rule is updated, the cache needs to be cleaned up.
     *
     * @see org.apache.shenyu.plugin.base.utils.CacheKeyUtils#getKey(org.apache.shenyu.common.dto.ConditionData, java.lang.String)
     */
    private static final ConcurrentMap<String, ConcurrentMap<Object, Set<String>>> MATCH_MAPPING = Maps.newConcurrentMap();

    /**
     * selectorId -> RuleData.
     */
    private static final ConcurrentMap<String, List<RuleData>> RULE_MAP = Maps.newConcurrentMap();

    private BaseDataCache() {
    }

    /**
     * Gets instance.
     *
     * @return the instance
     */
    public static BaseDataCache getInstance() {
        return INSTANCE;
    }

    /**
     * Cache plugin data.
     *
     * @param pluginData the plugin data
     */
    public void cachePluginData(final PluginData pluginData) {
        Optional.ofNullable(pluginData).ifPresent(data -> PLUGIN_MAP.put(data.getName(), data));
    }

    /**
     * Remove plugin data.
     *
     * @param pluginData the plugin data
     */
    public void removePluginData(final PluginData pluginData) {
        Optional.ofNullable(pluginData).ifPresent(data -> PLUGIN_MAP.remove(data.getName()));
    }

    /**
     * Remove plugin data by plugin name.
     *
     * @param pluginName the plugin name
     */
    public void removePluginDataByPluginName(final String pluginName) {
        PLUGIN_MAP.remove(pluginName);
    }

    /**
     * Clean plugin data.
     */
    public void cleanPluginData() {
        PLUGIN_MAP.clear();
    }

    /**
     * Clean plugin data self.
     *
     * @param pluginDataList the plugin data list
     */
    public void cleanPluginDataSelf(final List<PluginData> pluginDataList) {
        pluginDataList.forEach(this::removePluginData);
    }

    /**
     * Obtain plugin data plugin data.
     *
     * @param pluginName the plugin name
     * @return the plugin data
     */
    public PluginData obtainPluginData(final String pluginName) {
        return PLUGIN_MAP.get(pluginName);
    }

    /**
     * Cache select data.
     *
     * @param selectorData the selector data
     */
    public void cacheSelectData(final SelectorData selectorData) {
        Optional.ofNullable(selectorData).ifPresent(this::selectorAccept);
    }

    /**
     * Cache matched data.
     *
     * @param pluginName      the plugin name
     * @param cacheKey        the cache key
     * @param conditionParent the condition parent
     */
    public void cacheMatched(final String pluginName, final String cacheKey, final Object conditionParent) {
        final MemorySafeLRUMap<String, Object> cache = MATCH_CACHE.computeIfAbsent(pluginName, key -> new MemorySafeLRUMap<>(Constants.THE_256_MB, 1 << 16));
        cache.put(cacheKey, conditionParent);
        final ConcurrentMap<Object, Set<String>> mappings = MATCH_MAPPING.computeIfAbsent(pluginName, key -> Maps.newConcurrentMap());
        final Set<String> set = mappings.computeIfAbsent(conditionParent, key -> new HashSet<>());
        set.add(cacheKey);
    }

    /**
     * Remove select data.
     *
     * @param selectorData the selector data
     */
    public void removeSelectData(final SelectorData selectorData) {
        Optional.ofNullable(selectorData).ifPresent(data -> {
            final String pluginName = data.getPluginName();
            Optional.ofNullable(SELECTOR_MAP.get(pluginName))
                    .ifPresent(selectors -> selectors.removeIf(selector -> selector.getId().equals(data.getId())));
            Optional.ofNullable(data.getId()).ifPresent(SELECTOR_PLUGIN_MAP::remove);
            Optional.ofNullable(data.getConditionList()).ifPresent(conditions -> conditions.forEach(condition ->
                    Optional.ofNullable(condition.getId()).ifPresent(CONDITION_MAP::remove)));
            removeCache(data, pluginName);
        });
    }

    /**
     * Remove select data by plugin name.
     *
     * @param pluginName the plugin name
     */
    public void removeSelectDataByPluginName(final String pluginName) {
        final List<SelectorData> selectorDataList = SELECTOR_MAP.get(pluginName);
        SELECTOR_MAP.remove(pluginName);
        Optional.ofNullable(selectorDataList).ifPresent(selectors -> selectors.forEach(selector -> {
            Optional.ofNullable(selector.getId()).ifPresent(SELECTOR_PLUGIN_MAP::remove);
            Optional.ofNullable(selector.getConditionList()).ifPresent(conditions -> conditions.forEach(condition ->
                    Optional.ofNullable(condition.getId()).ifPresent(CONDITION_MAP::remove)));
            MATCH_CACHE.remove(pluginName);
            MATCH_MAPPING.remove(pluginName);
        }));
    }

    /**
     * Clean selector data.
     */
    public void cleanSelectorData() {
        SELECTOR_MAP.clear();
        SELECTOR_PLUGIN_MAP.clear();
        CONDITION_MAP.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof SelectorData)
                .map(Map.Entry::getKey)
                .forEach(CONDITION_MAP::remove);
        MATCH_CACHE.clear();
        MATCH_MAPPING.clear();
    }

    /**
     * Clean selector data self.
     *
     * @param selectorDataList the selector data list
     */
    public void cleanSelectorDataSelf(final List<SelectorData> selectorDataList) {
        selectorDataList.forEach(this::removeSelectData);
    }

    /**
     * Obtain selector data list list.
     *
     * @param pluginName the plugin name
     * @return the list
     */
    public List<SelectorData> obtainSelectorData(final String pluginName) {
        return SELECTOR_MAP.get(pluginName);
    }

    /**
     * Obtain selector/rule data by condition id.
     *
     * @param conditionId the condition id
     * @return the selector data
     */
    public Object getConditionParent(final String conditionId) {
        return Optional.ofNullable(conditionId)
                .map(CONDITION_MAP::get).orElse(null);
    }

    /**
     * Obtain matched selector/rule data.
     *
     * @param pluginName the plugin name
     * @param cacheKey   the cache key
     * @return the matched selector data
     */
    public Object obtainMatched(final String pluginName, final String cacheKey) {
        return Optional.ofNullable(MATCH_CACHE.get(pluginName))
                .map(cache -> cache.get(cacheKey)).orElse(null);
    }

    /**
     * Cache rule data.
     *
     * @param ruleData the rule data
     */
    public void cacheRuleData(final RuleData ruleData) {
        Optional.ofNullable(ruleData).ifPresent(this::ruleAccept);
    }

    /**
     * Remove rule data.
     *
     * @param ruleData the rule data
     */
    public void removeRuleData(final RuleData ruleData) {
        Optional.ofNullable(ruleData).ifPresent(data -> {
            final String selectorId = data.getSelectorId();
            final List<RuleData> ruleDataList = RULE_MAP.get(selectorId);
            Optional.ofNullable(ruleDataList).ifPresent(list -> list.removeIf(rule -> rule.getId().equals(data.getId())));
            Optional.ofNullable(data.getConditionDataList()).ifPresent(conditions -> conditions.forEach(condition ->
                    Optional.ofNullable(condition.getId()).ifPresent(CONDITION_MAP::remove)));
            Optional.ofNullable(SELECTOR_PLUGIN_MAP.get(selectorId))
                    .ifPresent(pluginName -> removeCache(data, pluginName));
        });
    }

    /**
     * Remove rule data by selector id.
     *
     * @param selectorId the selector id
     */
    public void removeRuleDataBySelectorId(final String selectorId) {
        RULE_MAP.remove(selectorId);
    }

    /**
     * Clean rule data.
     */
    public void cleanRuleData() {
        RULE_MAP.clear();
        CONDITION_MAP.entrySet().stream()
                .filter(entry -> entry.getValue() instanceof RuleData)
                .map(Map.Entry::getKey)
                .forEach(CONDITION_MAP::remove);
        MATCH_CACHE.clear();
        MATCH_MAPPING.clear();
    }

    /**
     * Clean rule data self.
     *
     * @param ruleDataList the rule data list
     */
    public void cleanRuleDataSelf(final List<RuleData> ruleDataList) {
        ruleDataList.forEach(this::removeRuleData);
    }

    /**
     * Obtain rule data list list.
     *
     * @param selectorId the selector id
     * @return the list
     */
    public List<RuleData> obtainRuleData(final String selectorId) {
        return RULE_MAP.get(selectorId);
    }

    /**
     * cache rule data.
     *
     * @param data the rule data
     */
    private void ruleAccept(final RuleData data) {
        String selectorId = data.getSelectorId();
        synchronized (RULE_MAP) {
            if (RULE_MAP.containsKey(selectorId)) {
                List<RuleData> existList = RULE_MAP.get(selectorId);
                final List<RuleData> resultList = existList.stream().filter(r -> !r.getId().equals(data.getId())).collect(Collectors.toList());
                resultList.add(data);
                final List<RuleData> collect = resultList.stream().sorted(Comparator.comparing(RuleData::getSort)).collect(Collectors.toList());
                RULE_MAP.put(selectorId, collect);
            } else {
                RULE_MAP.put(selectorId, Lists.newArrayList(data));
            }
        }
        // the update is also need to clean, but there is no way to distinguish between crate and update, so it is always clean
        Optional.ofNullable(SELECTOR_PLUGIN_MAP.get(selectorId))
                .ifPresent(pluginName -> Optional.ofNullable(MATCH_MAPPING.get(pluginName))
                        .map(cache -> cache.get(data))
                        .ifPresent(set -> Optional.ofNullable(MATCH_CACHE.get(pluginName)).ifPresent(cache -> set.forEach(cache::remove))));
        Optional.ofNullable(data.getConditionDataList())
                .ifPresent(conditions -> conditions.forEach(condition -> Optional.ofNullable(condition.getId())
                        .ifPresent(conditionId -> CONDITION_MAP.put(conditionId, data))));
    }

    /**
     * cache selector data.
     *
     * @param data the selector data
     */
    private void selectorAccept(final SelectorData data) {
        String key = data.getPluginName();
        synchronized (SELECTOR_MAP) {
            if (SELECTOR_MAP.containsKey(key)) {
                List<SelectorData> existList = SELECTOR_MAP.get(key);
                final List<SelectorData> resultList = existList.stream().filter(r -> !r.getId().equals(data.getId())).collect(Collectors.toList());
                resultList.add(data);
                final List<SelectorData> collect = resultList.stream().sorted(Comparator.comparing(SelectorData::getSort)).collect(Collectors.toList());
                SELECTOR_MAP.put(key, collect);
                for (SelectorData selector : collect) {
                    Optional.ofNullable(selector.getId()).ifPresent(selectorId -> SELECTOR_PLUGIN_MAP.put(selectorId, key));
                }
            } else {
                SELECTOR_MAP.put(key, Lists.newArrayList(data));
                Optional.ofNullable(data.getId()).ifPresent(selectorId -> SELECTOR_PLUGIN_MAP.put(selectorId, key));
            }
        }
        // the update is also need to clean, but there is no way to distinguish between crate and update, so it is always clean
        Optional.ofNullable(MATCH_MAPPING.get(key))
                .map(cache -> cache.get(data))
                .ifPresent(set -> Optional.ofNullable(MATCH_CACHE.get(key)).ifPresent(cache -> set.forEach(cache::remove)));
        Optional.ofNullable(data.getConditionList())
                .ifPresent(conditions -> conditions.forEach(condition -> Optional.ofNullable(condition.getId())
                        .ifPresent(conditionId -> CONDITION_MAP.put(conditionId, data))));
    }

    private void removeCache(final Object data, final String pluginName) {
        final ConcurrentMap<Object, Set<String>> mappings = MATCH_MAPPING.get(pluginName);
        Optional.ofNullable(mappings)
                .map(cache -> cache.get(data))
                .ifPresent(set -> {
                    Optional.ofNullable(MATCH_CACHE.get(pluginName)).ifPresent(cache -> set.forEach(cache::remove));
                    mappings.remove(data);
                });
    }
}
