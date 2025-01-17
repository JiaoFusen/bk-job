package com.tencent.bk.job.file_gateway.api.esb;

import com.tencent.bk.audit.annotations.AuditEntry;
import com.tencent.bk.audit.annotations.AuditRequestBody;
import com.tencent.bk.job.common.constant.ErrorCode;
import com.tencent.bk.job.common.esb.model.EsbResp;
import com.tencent.bk.job.common.exception.FailedPreconditionException;
import com.tencent.bk.job.common.exception.InvalidParamException;
import com.tencent.bk.job.common.iam.constant.ActionId;
import com.tencent.bk.job.common.service.AppScopeMappingService;
import com.tencent.bk.job.file_gateway.consts.WorkerSelectModeEnum;
import com.tencent.bk.job.file_gateway.consts.WorkerSelectScopeEnum;
import com.tencent.bk.job.file_gateway.model.dto.FileSourceDTO;
import com.tencent.bk.job.file_gateway.model.dto.FileSourceTypeDTO;
import com.tencent.bk.job.file_gateway.model.req.esb.v3.EsbCreateOrUpdateFileSourceV3Req;
import com.tencent.bk.job.file_gateway.model.resp.esb.v3.EsbFileSourceSimpleInfoV3DTO;
import com.tencent.bk.job.file_gateway.service.FileSourceService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@Slf4j
public class EsbFileSourceV3ResourceImpl implements EsbFileSourceV3Resource {

    private final FileSourceService fileSourceService;
    private final AppScopeMappingService appScopeMappingService;

    @Autowired
    public EsbFileSourceV3ResourceImpl(FileSourceService fileSourceService,
                                       AppScopeMappingService appScopeMappingService) {
        this.fileSourceService = fileSourceService;
        this.appScopeMappingService = appScopeMappingService;
    }

    @Override
    @AuditEntry(actionId = ActionId.CREATE_FILE_SOURCE)
    public EsbResp<EsbFileSourceSimpleInfoV3DTO> createFileSource(
        @AuditRequestBody EsbCreateOrUpdateFileSourceV3Req req) {
        req.fillAppResourceScope(appScopeMappingService);
        Long appId = req.getAppId();
        checkCreateParam(req);
        FileSourceDTO fileSourceDTO = buildFileSourceDTO(req.getUserName(), appId, null, req);
        FileSourceDTO createdFileSource = fileSourceService.saveFileSource(req.getUserName(), appId, fileSourceDTO);
        return EsbResp.buildSuccessResp(new EsbFileSourceSimpleInfoV3DTO(createdFileSource.getId()));
    }

    @Override
    @AuditEntry(actionId = ActionId.MANAGE_FILE_SOURCE)
    public EsbResp<EsbFileSourceSimpleInfoV3DTO> updateFileSource(
        @AuditRequestBody EsbCreateOrUpdateFileSourceV3Req req) {
        req.fillAppResourceScope(appScopeMappingService);
        Integer id = checkUpdateParamAndGetId(req);
        Long appId = req.getAppId();
        FileSourceDTO fileSourceDTO = buildFileSourceDTO(req.getUserName(), appId, id, req);
        FileSourceDTO updateFileSource = fileSourceService.updateFileSourceById(
            req.getUserName(), appId, fileSourceDTO);
        return EsbResp.buildSuccessResp(new EsbFileSourceSimpleInfoV3DTO(updateFileSource.getId()));
    }

    private void checkCommonParam(EsbCreateOrUpdateFileSourceV3Req req) {
        if (StringUtils.isBlank(req.getAlias())) {
            throw new InvalidParamException(ErrorCode.MISSING_PARAM_WITH_PARAM_NAME, new String[]{"alias"});
        }
        if (StringUtils.isBlank(req.getType())) {
            throw new InvalidParamException(ErrorCode.MISSING_PARAM_WITH_PARAM_NAME, new String[]{"type"});
        }
        if (StringUtils.isBlank(req.getCredentialId())) {
            throw new InvalidParamException(ErrorCode.MISSING_PARAM_WITH_PARAM_NAME, new String[]{"credential_id"});
        }
    }

    private void checkCreateParam(EsbCreateOrUpdateFileSourceV3Req req) {
        String code = req.getCode();
        if (StringUtils.isBlank(code)) {
            throw new InvalidParamException(ErrorCode.MISSING_PARAM_WITH_PARAM_NAME,
                new String[]{"code"});
        }
        FileSourceTypeDTO fileSourceTypeDTO = fileSourceService.getFileSourceTypeByCode(
            req.getType()
        );
        if (fileSourceTypeDTO == null) {
            throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME,
                new String[]{"type"});
        }
        if (fileSourceService.existsCode(req.getAppId(), code)) {
            throw new FailedPreconditionException(ErrorCode.FILE_SOURCE_CODE_ALREADY_EXISTS, new String[]{code});
        }
        checkCommonParam(req);
    }

    private Integer checkUpdateParamAndGetId(EsbCreateOrUpdateFileSourceV3Req req) {
        Long appId = req.getAppId();
        String code = req.getCode();
        Integer id = fileSourceService.getFileSourceIdByCode(appId, code);
        if (id == null) {
            throw new FailedPreconditionException(ErrorCode.FAIL_TO_FIND_FILE_SOURCE_BY_CODE, new String[]{code});
        }
        if (!fileSourceService.existsFileSource(appId, id)) {
            throw new FailedPreconditionException(ErrorCode.FILE_SOURCE_ID_NOT_IN_BIZ, new String[]{id.toString()});
        }
        if (StringUtils.isNotBlank(req.getType())) {
            FileSourceTypeDTO fileSourceTypeDTO = fileSourceService.getFileSourceTypeByCode(
                req.getType()
            );
            if (fileSourceTypeDTO == null) {
                throw new InvalidParamException(ErrorCode.ILLEGAL_PARAM_WITH_PARAM_NAME,
                    new String[]{"type"});
            }
        }
        return id;
    }

    private FileSourceDTO buildFileSourceDTO(String username,
                                             Long appId,
                                             Integer id,
                                             EsbCreateOrUpdateFileSourceV3Req fileSourceCreateUpdateReq) {
        FileSourceDTO fileSourceDTO = new FileSourceDTO();
        fileSourceDTO.setAppId(appId);
        fileSourceDTO.setId(id);
        fileSourceDTO.setCode(fileSourceCreateUpdateReq.getCode());
        fileSourceDTO.setAlias(fileSourceCreateUpdateReq.getAlias());
        fileSourceDTO.setStatus(null);
        fileSourceDTO.setFileSourceType(
            fileSourceService.getFileSourceTypeByCode(
                fileSourceCreateUpdateReq.getType()
            )
        );
        fileSourceDTO.setFileSourceInfoMap(fileSourceCreateUpdateReq.getAccessParams());
        fileSourceDTO.setPublicFlag(false);
        fileSourceDTO.setSharedAppIdList(Collections.emptyList());
        fileSourceDTO.setShareToAllApp(false);
        fileSourceDTO.setCredentialId(fileSourceCreateUpdateReq.getCredentialId());
        fileSourceDTO.setFilePrefix(fileSourceCreateUpdateReq.getFilePrefix());
        fileSourceDTO.setWorkerSelectScope(WorkerSelectScopeEnum.PUBLIC.name());
        fileSourceDTO.setWorkerSelectMode(WorkerSelectModeEnum.AUTO.name());
        fileSourceDTO.setWorkerId(null);
        // 文件源默认开启状态
        fileSourceDTO.setEnable(true);
        fileSourceDTO.setCreator(username);
        fileSourceDTO.setCreateTime(System.currentTimeMillis());
        fileSourceDTO.setLastModifyUser(username);
        fileSourceDTO.setLastModifyTime(System.currentTimeMillis());
        return fileSourceDTO;
    }
}
