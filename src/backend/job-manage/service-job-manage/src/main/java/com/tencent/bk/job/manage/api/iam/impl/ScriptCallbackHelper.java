package com.tencent.bk.job.manage.api.iam.impl;

import com.tencent.bk.audit.utils.json.JsonSchemaUtils;
import com.tencent.bk.job.common.iam.util.IamRespUtil;
import com.tencent.bk.job.common.model.PageData;
import com.tencent.bk.job.manage.model.dto.ScriptBasicDTO;
import com.tencent.bk.job.manage.model.dto.ScriptDTO;
import com.tencent.bk.job.manage.model.esb.v3.response.EsbScriptV3DTO;
import com.tencent.bk.job.manage.model.query.ScriptQuery;
import com.tencent.bk.job.manage.service.ApplicationService;
import com.tencent.bk.job.manage.service.ScriptService;
import com.tencent.bk.sdk.iam.dto.callback.request.CallbackRequestDTO;
import com.tencent.bk.sdk.iam.dto.callback.request.IamSearchCondition;
import com.tencent.bk.sdk.iam.dto.callback.response.CallbackBaseResponseDTO;
import com.tencent.bk.sdk.iam.dto.callback.response.FetchResourceTypeSchemaResponseDTO;
import com.tencent.bk.sdk.iam.dto.callback.response.ListInstanceResponseDTO;
import com.tencent.bk.sdk.iam.dto.callback.response.SearchInstanceResponseDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ScriptCallbackHelper extends AbstractScriptCallbackHelper {
    private final ScriptService scriptService;

    @Autowired
    public ScriptCallbackHelper(ScriptService scriptService,
                                ApplicationService applicationService) {
        super(applicationService);
        this.scriptService = scriptService;
    }

    @Override
    protected ListInstanceResponseDTO listInstanceResp(CallbackRequestDTO callbackRequest) {
        ScriptQuery scriptQuery = buildBasicScriptQuery(callbackRequest);
        PageData<ScriptDTO> scriptDTOPageData = scriptService.listPageScript(scriptQuery);

        return IamRespUtil.getListInstanceRespFromPageData(scriptDTOPageData, this::convert);
    }


    @Override
    protected SearchInstanceResponseDTO searchInstanceResp(CallbackRequestDTO callbackRequest) {

        ScriptQuery scriptQuery = buildBasicScriptQuery(callbackRequest);
        scriptQuery.setName(callbackRequest.getFilter().getKeyword());
        PageData<ScriptDTO> accountDTOPageData = scriptService.listPageScript(scriptQuery);

        return IamRespUtil.getSearchInstanceRespFromPageData(accountDTOPageData, this::convert);
    }

    @Override
    protected CallbackBaseResponseDTO fetchInstanceResp(CallbackRequestDTO callbackRequest) {
        IamSearchCondition searchCondition = IamSearchCondition.fromReq(callbackRequest);
        List<String> scriptIdList = searchCondition.getIdList();
        List<ScriptBasicDTO> scriptBasicDTOList = scriptService.listScriptBasicInfoByScriptIds(scriptIdList);
        return buildFetchInstanceResp(scriptIdList, scriptBasicDTOList);
    }

    @Override
    boolean isPublicScript() {
        return false;
    }

    public CallbackBaseResponseDTO doCallback(CallbackRequestDTO callbackRequest) {
        return baseCallback(callbackRequest);
    }

    @Override
    protected FetchResourceTypeSchemaResponseDTO fetchResourceTypeSchemaResp(
        CallbackRequestDTO callbackRequest) {
        FetchResourceTypeSchemaResponseDTO resp = new FetchResourceTypeSchemaResponseDTO();
        resp.setData(JsonSchemaUtils.generateJsonSchema(EsbScriptV3DTO.class));
        return resp;
    }
}
