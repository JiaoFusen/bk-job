/*
 * Tencent is pleased to support the open source community by making BK-JOB蓝鲸智云作业平台 available.
 *
 * Copyright (C) 2021 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-JOB蓝鲸智云作业平台 is licensed under the MIT License.
 *
 * License for BK-JOB蓝鲸智云作业平台:
 * --------------------------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE.
 */

package com.tencent.bk.job.common.util.feature;

import com.tencent.bk.job.common.config.FeatureConfig;
import com.tencent.bk.job.common.config.FeatureToggleConfig;
import com.tencent.bk.job.common.config.ToggleStrategyConfig;
import com.tencent.bk.job.common.util.ApplicationContextRegister;
import com.tencent.bk.job.common.util.feature.strategy.ResourceScopeBlackListToggleStrategy;
import com.tencent.bk.job.common.util.feature.strategy.ResourceScopeWhiteListToggleStrategy;
import com.tencent.bk.job.common.util.feature.strategy.ToggleStrategy;
import com.tencent.bk.job.common.util.feature.strategy.WeightToggleStrategy;
import com.tencent.bk.job.common.util.json.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * 特性开关
 */
@Slf4j
public class FeatureToggle {

    /**
     * key: featureId; value: Feature
     */
    private static volatile Map<String, Feature> features = new HashMap<>();
    private static volatile boolean isInitial = false;

    public static void reload() {
        synchronized (FeatureToggle.class) {
            if (load()) {
                isInitial = true;
            }
        }
    }

    public static boolean load() {
        log.info("Load feature toggle start ..");
        FeatureToggleConfig featureToggleConfig = ApplicationContextRegister.getBean(FeatureToggleConfig.class);

        if (featureToggleConfig.getFeatures() == null || featureToggleConfig.getFeatures().isEmpty()) {
            log.info("Feature toggle config empty!");
            return true;
        }

        // 加载特性配置是否成功
        boolean loadSuccess = true;
        Map<String, Feature> tmpFeatures = new HashMap<>();
        try {
            featureToggleConfig.getFeatures().forEach((featureId, featureConfig) -> {
                Feature feature = parseFeatureConfig(featureId, featureConfig);
                tmpFeatures.put(featureId, feature);
            });
        } catch (Throwable e) {
            log.error("Load feature toggle config caught exception", e);
            loadSuccess = false;
        }

        // 只有特性的配置正确加载，才替换掉原来的配置
        if (loadSuccess) {
            // 使用新的配置完全替换老的配置
            features = tmpFeatures;
            log.info("Load feature toggle config successfully! features: {}", JsonUtils.toJson(features));
        } else {
            log.error("Load feature toggle config fail, skip update feature toggle config! features: {}",
                JsonUtils.toJson(features));
        }

        return loadSuccess;
    }

    private static Feature parseFeatureConfig(String featureId, FeatureConfig featureConfig) {
        Feature feature = new Feature();
        feature.setId(featureId);
        feature.setEnabled(featureConfig.isEnabled());

        if (featureConfig.isEnabled()) {
            ToggleStrategyConfig strategyConfig = featureConfig.getStrategy();
            if (strategyConfig != null) {
                String strategyId = strategyConfig.getId();
                ToggleStrategy toggleStrategy = null;
                switch (strategyId) {
                    case ResourceScopeWhiteListToggleStrategy.STRATEGY_ID:
                        toggleStrategy = new ResourceScopeWhiteListToggleStrategy(featureId,
                            strategyConfig.getParams());
                        break;
                    case ResourceScopeBlackListToggleStrategy.STRATEGY_ID:
                        toggleStrategy = new ResourceScopeBlackListToggleStrategy(featureId,
                            strategyConfig.getParams());
                        break;
                    case WeightToggleStrategy.STRATEGY_ID:
                        toggleStrategy = new WeightToggleStrategy(featureId, strategyConfig.getParams());
                        break;
                    default:
                        log.error("Unsupported toggle strategy: {} for feature: {}, ignore it!", strategyId,
                            featureId);
                        break;
                }
                if (toggleStrategy != null) {
                    feature.setStrategy(toggleStrategy);
                }
            }
        }
        return feature;
    }


    /**
     * 判断特性是否开启
     *
     * @param featureId 特性ID
     * @param ctx       特性运行上下文
     * @return 是否开启
     */
    public static boolean checkFeature(String featureId, FeatureExecutionContext ctx) {
        if (!isInitial) {
            synchronized (FeatureConfig.class) {
                if (!isInitial) {
                    if (load()) {
                        isInitial = true;
                    }
                }
            }
        }

        Feature feature = features.get(featureId);
        if (feature == null) {
            log.debug("Feature: {} is not exist!", featureId);
            return false;
        }
        if (!feature.isEnabled()) {
            log.debug("Feature: {} is disabled!", featureId);
            return false;
        }

        ToggleStrategy strategy = feature.getStrategy();
        if (strategy == null) {
            // 如果没有配置特性开启策略，且enabled=true，判定为特性开启
            return true;
        }
        return strategy.evaluate(featureId, ctx);
    }
}
