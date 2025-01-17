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

package com.tencent.bk.job.execute.service.impl;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.tencent.bk.job.common.cc.model.CcCloudAreaInfoDTO;
import com.tencent.bk.job.common.cc.model.CcCloudIdDTO;
import com.tencent.bk.job.common.cc.model.CcInstanceDTO;
import com.tencent.bk.job.common.cc.model.DynamicGroupHostPropDTO;
import com.tencent.bk.job.common.cc.sdk.CmdbClientFactory;
import com.tencent.bk.job.common.cc.sdk.IBizCmdbClient;
import com.tencent.bk.job.common.model.InternalResponse;
import com.tencent.bk.job.common.model.dto.ApplicationHostDTO;
import com.tencent.bk.job.common.model.dto.HostDTO;
import com.tencent.bk.job.common.model.dto.ResourceScope;
import com.tencent.bk.job.common.service.AppScopeMappingService;
import com.tencent.bk.job.execute.model.DynamicServerGroupDTO;
import com.tencent.bk.job.execute.model.DynamicServerTopoNodeDTO;
import com.tencent.bk.job.execute.service.HostService;
import com.tencent.bk.job.manage.api.inner.ServiceHostResource;
import com.tencent.bk.job.manage.model.inner.ServiceHostDTO;
import com.tencent.bk.job.manage.model.inner.ServiceListAppHostResultDTO;
import com.tencent.bk.job.manage.model.inner.request.ServiceBatchGetAppHostsReq;
import com.tencent.bk.job.manage.model.inner.request.ServiceBatchGetHostsReq;
import com.tencent.bk.job.manage.model.inner.request.ServiceGetHostsByCloudIpv6Req;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service("jobExecuteHostService")
@Slf4j
public class HostServiceImpl implements HostService {
    private final ServiceHostResource hostResource;
    private final AppScopeMappingService appScopeMappingService;
    private final ExecutorService getHostsByTopoExecutor;
    private static final String UNKNOWN_CLOUD_AREA_NAME = "Unknown";

    private final LoadingCache<Long, String> cloudAreaNameCache = Caffeine.newBuilder()
        .maximumSize(10000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .recordStats()
        .build(new CacheLoader<Long, String>() {
            @Override
            public String load(@NotNull Long bkCloudId) {
                IBizCmdbClient bizCmdbClient = CmdbClientFactory.getCmdbClient();
                CcCloudAreaInfoDTO cloudArea = bizCmdbClient.getCloudAreaByBkCloudId(bkCloudId);
                // 默认设置为"Unknown",避免本地缓存穿透
                return cloudArea == null ? UNKNOWN_CLOUD_AREA_NAME : cloudArea.getName();
            }

            @NotNull
            @Override
            public Map<Long, String> loadAll(@NotNull Iterable<? extends Long> bkCloudIds) {
                IBizCmdbClient bizCmdbClient = CmdbClientFactory.getCmdbClient();
                List<CcCloudAreaInfoDTO> cloudAreaList = bizCmdbClient.getCloudAreaList();
                Map<Long, String> result = new HashMap<>();
                // 默认设置为"Unknown",避免本地缓存穿透
                bkCloudIds.forEach(bkCloudId -> result.put(bkCloudId, UNKNOWN_CLOUD_AREA_NAME));

                if (CollectionUtils.isEmpty(cloudAreaList)) {
                    log.warn("Get all cloud area return empty!");
                    return result;
                }

                cloudAreaList.forEach(cloudArea -> result.put(cloudArea.getId(), cloudArea.getName()));
                return result;
            }
        });

    @Autowired
    public HostServiceImpl(ServiceHostResource hostResource,
                           AppScopeMappingService appScopeMappingService,
                           @Qualifier("getHostsByTopoExecutor") ExecutorService getHostsByTopoExecutor,
                           MeterRegistry meterRegistry) {
        this.hostResource = hostResource;
        this.appScopeMappingService = appScopeMappingService;
        this.getHostsByTopoExecutor = getHostsByTopoExecutor;
        CaffeineCacheMetrics.monitor(meterRegistry, cloudAreaNameCache, "cloudAreaNameCache");
    }

    @Override
    public Map<HostDTO, ServiceHostDTO> batchGetHosts(List<HostDTO> hostIps) {
        List<ServiceHostDTO> hosts = hostResource.batchGetHosts(
            new ServiceBatchGetHostsReq(hostIps)).getData();
        Map<HostDTO, ServiceHostDTO> hostMap = new HashMap<>();
        hosts.forEach(host -> hostMap.put(new HostDTO(host.getCloudAreaId(), host.getIp()), host));
        return hostMap;
    }

    @Override
    public ServiceHostDTO getHost(HostDTO host) {
        List<ServiceHostDTO> hosts = hostResource.batchGetHosts(
            new ServiceBatchGetHostsReq(Collections.singletonList(host))).getData();
        if (CollectionUtils.isEmpty(hosts)) {
            return null;
        }
        return hosts.get(0);
    }

    @Override
    public ServiceHostDTO getHostByCloudIpv6(long cloudAreaId, String ipv6) {
        List<ServiceHostDTO> hosts = hostResource.getHostsByCloudIpv6(
            new ServiceGetHostsByCloudIpv6Req(cloudAreaId, ipv6)
        ).getData();
        if (CollectionUtils.isEmpty(hosts)) {
            log.warn("Cannot find host by (cloudAreaId={}, ipv6={})", cloudAreaId, ipv6);
            return null;
        } else if (hosts.size() > 1) {
            log.warn(
                "Found {} host by (cloudAreaId={}, ipv6={}), use first one, hosts={}",
                hosts.size(),
                cloudAreaId,
                ipv6,
                hosts
            );
        }
        return hosts.get(0);
    }

    @Override
    public ServiceListAppHostResultDTO batchGetAppHosts(Long appId,
                                                        Collection<HostDTO> hosts,
                                                        boolean refreshAgentId) {
        InternalResponse<ServiceListAppHostResultDTO> response =
            hostResource.batchGetAppHosts(appId,
                new ServiceBatchGetAppHostsReq(new ArrayList<>(hosts), refreshAgentId));
        return response.getData();
    }

    @Override
    public List<HostDTO> getHostsByDynamicGroupId(long appId, String groupId) {
        IBizCmdbClient bizCmdbClient = CmdbClientFactory.getCmdbClient();
        ResourceScope resourceScope = appScopeMappingService.getScopeByAppId(appId);
        List<DynamicGroupHostPropDTO> cmdbGroupHostList =
            bizCmdbClient.getDynamicGroupIp(Long.parseLong(resourceScope.getId()), groupId);
        List<HostDTO> hostList = new ArrayList<>();
        if (cmdbGroupHostList == null || cmdbGroupHostList.isEmpty()) {
            return hostList;
        }
        for (DynamicGroupHostPropDTO hostProp : cmdbGroupHostList) {
            List<CcCloudIdDTO> hostCloudIdList = hostProp.getCloudIdList();
            if (hostCloudIdList == null || hostCloudIdList.isEmpty()) {
                log.warn("Get ip by dynamic group id, cmdb return illegal host, skip it!appId={}, " +
                    "groupId={}, " +
                    "hostIp={}", appId, groupId, hostProp.getInnerIp());
                continue;
            }
            CcCloudIdDTO hostCloudId = hostCloudIdList.get(0);
            if (hostCloudId == null) {
                log.warn("Get ip by dynamic group id, cmdb return illegal host, skip it!appId={}, " +
                    "groupId={}, " +
                    "hostIp={}", appId, groupId, hostProp.getInnerIp());
                continue;
            }
            HostDTO host = new HostDTO();
            host.setHostId(hostProp.getId());
            host.setBkCloudId(hostCloudId.getInstanceId());
            host.setIp(hostProp.getFirstIp());
            host.setIpv6(hostProp.getIpv6());
            host.setAgentId(hostProp.getAgentId());
            hostList.add(host);
        }
        log.info("Get hosts by groupId, appId={}, groupId={}, hosts={}", appId, groupId, hostList);
        return hostList;
    }

    @Override
    public Map<DynamicServerGroupDTO, List<HostDTO>> batchGetAndGroupHostsByDynamicGroup(
        long appId,
        Collection<DynamicServerGroupDTO> groups) {
        if (CollectionUtils.isEmpty(groups)) {
            return new HashMap<>();
        }

        Map<DynamicServerGroupDTO, List<HostDTO>> result = new HashMap<>();
        groups.forEach(group -> result.put(group, getHostsByDynamicGroupId(appId, group.getGroupId())));
        return result;
    }

    @Override
    public List<HostDTO> getHostsByTopoNodes(long appId, List<CcInstanceDTO> ccInstances) {
        IBizCmdbClient bizCmdbClient = CmdbClientFactory.getCmdbClient();
        ResourceScope resourceScope = appScopeMappingService.getScopeByAppId(appId);
        long bizId = Long.parseLong(resourceScope.getId());
        List<ApplicationHostDTO> appHosts = bizCmdbClient.getHosts(bizId, ccInstances);
        List<HostDTO> ips = new ArrayList<>();
        if (appHosts == null || appHosts.isEmpty()) {
            return ips;
        }
        for (ApplicationHostDTO hostProp : appHosts) {
            HostDTO host = new HostDTO(hostProp.getCloudAreaId(), hostProp.getIp());
            host.setHostId(hostProp.getHostId());
            host.setIpv6(hostProp.getIpv6());
            host.setAgentId(hostProp.getAgentId());
            ips.add(host);
        }
        log.info("Get hosts by cc topo nodes, appId={}, nodes={}, hosts={}", appId, ccInstances, ips);
        return ips;
    }

    @Override
    public Map<DynamicServerTopoNodeDTO, List<HostDTO>> getAndGroupHostsByTopoNodes(
        long appId,
        Collection<DynamicServerTopoNodeDTO> topoNodes) {

        Map<DynamicServerTopoNodeDTO, List<HostDTO>> result;
        if (topoNodes.size() < 10) {
            result = new HashMap<>();
            for (DynamicServerTopoNodeDTO topoNode : topoNodes) {
                List<HostDTO> topoHosts = getHostsByTopoNodes(appId,
                    Collections.singletonList(new CcInstanceDTO(topoNode.getNodeType(),
                        topoNode.getTopoNodeId())));
                topoNode.setIpList(topoHosts);
                result.put(topoNode, topoHosts);
            }
        } else {
            result = getTopoHostsConcurrent(appId, topoNodes);
        }
        return result;
    }

    private Map<DynamicServerTopoNodeDTO, List<HostDTO>> getTopoHostsConcurrent(
        long appId,
        Collection<DynamicServerTopoNodeDTO> topoNodes) {

        Map<DynamicServerTopoNodeDTO, List<HostDTO>> result = new HashMap<>();

        CountDownLatch latch = new CountDownLatch(topoNodes.size());
        List<Future<Pair<DynamicServerTopoNodeDTO, List<HostDTO>>>> futures =
            new ArrayList<>(topoNodes.size());
        for (DynamicServerTopoNodeDTO topoNode : topoNodes) {
            futures.add(getHostsByTopoExecutor.submit(new GetTopoHostTask(appId, topoNode, latch)));
        }

        try {
            for (Future<Pair<DynamicServerTopoNodeDTO, List<HostDTO>>> future : futures) {
                Pair<DynamicServerTopoNodeDTO, List<HostDTO>> topoAndHosts = future.get();
                for (DynamicServerTopoNodeDTO topoNode : topoNodes) {
                    if (topoNode.equals(topoAndHosts.getLeft())) {
                        result.put(topoNode, topoAndHosts.getRight());
                    }
                }
            }
        } catch (InterruptedException | ExecutionException e) {
            log.error("Get topo hosts concurrent error", e);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            log.error("Get topo hosts concurrent error", e);
        }
        return result;
    }

    @Override
    public Map<Long, String> batchGetCloudAreaNames(Collection<Long> bkCloudIds) {
        if (CollectionUtils.isEmpty(bkCloudIds)) {
            return Collections.emptyMap();
        }
        try {
            long start = System.currentTimeMillis();
            Map<Long, String> cloudAreaIdNames = cloudAreaNameCache.getAll(bkCloudIds);
            long cost = System.currentTimeMillis() - start;
            if (cost > 1000) {
                log.warn("Batch get cloud area names slow, cost: {}", cost);
            }
            return cloudAreaIdNames;
        } catch (Exception e) {
            log.warn("Fail to get cloud area name", e);
            // 降级实现
            Map<Long, String> failResult = new HashMap<>();
            bkCloudIds.forEach(bkCloudId -> failResult.put(bkCloudId, UNKNOWN_CLOUD_AREA_NAME));
            return failResult;
        }
    }

    private class GetTopoHostTask implements Callable<Pair<DynamicServerTopoNodeDTO, List<HostDTO>>> {
        private final long appId;
        private final DynamicServerTopoNodeDTO topoNode;
        private final CountDownLatch latch;

        private GetTopoHostTask(long appId, DynamicServerTopoNodeDTO topoNode, CountDownLatch latch) {
            this.appId = appId;
            this.topoNode = topoNode;
            this.latch = latch;
        }

        @Override
        public Pair<DynamicServerTopoNodeDTO, List<HostDTO>> call() {
            try {
                List<HostDTO> topoHosts = getHostsByTopoNodes(appId,
                    Collections.singletonList(new CcInstanceDTO(topoNode.getNodeType(), topoNode.getTopoNodeId())));
                return new ImmutablePair<>(topoNode, topoHosts);
            } catch (Throwable e) {
                log.warn("Get hosts by topo fail", e);
                return new ImmutablePair<>(topoNode, Collections.emptyList());
            } finally {
                latch.countDown();
            }
        }
    }
}
