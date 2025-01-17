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

package com.tencent.bk.job.common.gse.config;

import com.tencent.bk.job.common.crypto.Encryptor;
import com.tencent.bk.job.common.crypto.RSAEncryptor;
import com.tencent.bk.job.common.gse.GseClient;
import com.tencent.bk.job.common.gse.constants.GseConstants;
import com.tencent.bk.job.common.gse.service.AgentStateClient;
import com.tencent.bk.job.common.gse.service.AgentStateClientImpl;
import com.tencent.bk.job.common.gse.v1.GseV1ApiClient;
import com.tencent.bk.job.common.gse.v1.config.GseV1AutoConfiguration;
import com.tencent.bk.job.common.gse.v2.GseV2ApiClient;
import com.tencent.bk.job.common.gse.v2.GseV2AutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.security.GeneralSecurityException;


@Configuration(proxyBeanMethods = false)
@Import(
    {
        AgentStateQueryConfig.class,
        GseV1AutoConfiguration.class,
        GseV2AutoConfiguration.class
    }
)
public class GseAutoConfiguration {

    @Bean("GseApiClient")
    public GseClient gseClient(ObjectProvider<GseV1ApiClient> gseV1ApiClient,
                               ObjectProvider<GseV2ApiClient> gseV2ApiClient) {
        return new GseClient(gseV1ApiClient.getIfAvailable(),
            gseV2ApiClient.getIfAvailable());
    }

    @Bean("AgentStateClient")
    public AgentStateClient agentStateClient(AgentStateQueryConfig agentStateQueryConfig,
                                             GseClient gseClient) {
        return new AgentStateClientImpl(agentStateQueryConfig, gseClient);
    }

    @Bean("gseRsaEncryptor")
    public Encryptor rsaEncryptor() throws IOException, GeneralSecurityException {
        return new RSAEncryptor(GseConstants.publicKeyPermBase64);
    }
}
