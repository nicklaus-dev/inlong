/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.plugin.listener;

import org.apache.inlong.manager.common.consts.InlongConstants;
import org.apache.inlong.manager.common.enums.GroupOperateType;
import org.apache.inlong.manager.common.enums.TaskEvent;
import org.apache.inlong.manager.common.util.JsonUtils;
import org.apache.inlong.manager.plugin.flink.FlinkOperation;
import org.apache.inlong.manager.plugin.flink.dto.FlinkInfo;
import org.apache.inlong.manager.plugin.flink.enums.Constants;
import org.apache.inlong.manager.pojo.sink.StreamSink;
import org.apache.inlong.manager.pojo.stream.InlongStreamExtInfo;
import org.apache.inlong.manager.pojo.stream.InlongStreamInfo;
import org.apache.inlong.manager.pojo.workflow.form.process.GroupResourceProcessForm;
import org.apache.inlong.manager.pojo.workflow.form.process.ProcessForm;
import org.apache.inlong.manager.workflow.WorkflowContext;
import org.apache.inlong.manager.workflow.event.ListenerResult;
import org.apache.inlong.manager.workflow.event.task.SortOperateListener;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.common.JobStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.inlong.manager.plugin.util.FlinkUtils.getExceptionStackMsg;

/**
 * Listener of restart sort.
 */
@Slf4j
public class RestartSortListener implements SortOperateListener {

    @Override
    public TaskEvent event() {
        return TaskEvent.COMPLETE;
    }

    @Override
    public boolean accept(WorkflowContext workflowContext) {
        ProcessForm processForm = workflowContext.getProcessForm();
        String groupId = processForm.getInlongGroupId();
        if (!(processForm instanceof GroupResourceProcessForm)) {
            log.info("not add restart group listener, not GroupResourceProcessForm for groupId [{}]", groupId);
            return false;
        }

        GroupResourceProcessForm groupProcessForm = (GroupResourceProcessForm) processForm;
        if (groupProcessForm.getGroupOperateType() != GroupOperateType.RESTART) {
            log.info("not add restart group listener, as the operate was not RESTART for groupId [{}]", groupId);
            return false;
        }

        log.info("add restart group listener for groupId [{}]", groupId);
        return true;
    }

    @Override
    public ListenerResult listen(WorkflowContext context) throws Exception {
        ProcessForm processForm = context.getProcessForm();
        String groupId = processForm.getInlongGroupId();
        if (!(processForm instanceof GroupResourceProcessForm)) {
            String message = String.format("process form was not GroupResourceProcessForm for groupId [%s]", groupId);
            log.error(message);
            return ListenerResult.fail(message);
        }

        GroupResourceProcessForm groupResourceProcessForm = (GroupResourceProcessForm) processForm;

        List<InlongStreamInfo> streamInfos = groupResourceProcessForm.getStreamInfos();
        for (InlongStreamInfo streamInfo : streamInfos) {
            List<StreamSink> sinkList = streamInfo.getSinkList();
            if (CollectionUtils.isEmpty(sinkList)) {
                continue;
            }
            List<InlongStreamExtInfo> extList = streamInfo.getExtList();
            log.info("stream ext info: {}", extList);

            Map<String, String> kvConf = new HashMap<>();
            extList.forEach(v -> kvConf.put(v.getKeyName(), v.getKeyValue()));
            String sortExt = kvConf.get(InlongConstants.SORT_PROPERTIES);
            if (StringUtils.isNotEmpty(sortExt)) {
                Map<String, String> result = JsonUtils.OBJECT_MAPPER.convertValue(
                        JsonUtils.OBJECT_MAPPER.readTree(sortExt), new TypeReference<Map<String, String>>() {
                        });
                kvConf.putAll(result);
            }

            String jobId = kvConf.get(InlongConstants.SORT_JOB_ID);
            if (StringUtils.isBlank(jobId)) {
                String message = String.format("sort job id is empty for groupId [%s], streamId [%s]", groupId,
                        streamInfo.getInlongStreamId());
                return ListenerResult.fail(message);
            }
            String dataflow = kvConf.get(InlongConstants.DATAFLOW);
            if (StringUtils.isEmpty(dataflow)) {
                String message = String.format("dataflow is empty for groupId [%s], streamId [%s]", groupId,
                        streamInfo.getInlongStreamId());
                log.error(message);
                return ListenerResult.fail(message);
            }

            FlinkInfo flinkInfo = new FlinkInfo();
            String jobName = Constants.SORT_JOB_NAME_GENERATOR.apply(processForm) + InlongConstants.HYPHEN
                    + streamInfo.getInlongStreamId();
            flinkInfo.setJobName(jobName);
            String sortUrl = kvConf.get(InlongConstants.SORT_URL);
            flinkInfo.setEndpoint(sortUrl);
            FlinkOperation flinkOperation = FlinkOperation.getInstance();
            try {
                flinkOperation.genPath(flinkInfo, dataflow);
                // todo Currently, savepoint is not being used to restart, but will be improved in the future
                flinkOperation.start(flinkInfo);
                log.info("job restart success for groupId = {}, streamId = {} jobId = {}", groupId,
                        streamInfo.getInlongStreamId(), jobId);
            } catch (Exception e) {
                flinkInfo.setException(true);
                flinkInfo.setExceptionMsg(getExceptionStackMsg(e));
                flinkOperation.pollJobStatus(flinkInfo, JobStatus.RUNNING);

                String message = String.format("restart sort failed for groupId [%s], streamId [%s] ", groupId,
                        streamInfo.getInlongStreamId());
                log.error(message, e);
                return ListenerResult.fail(message + e.getMessage());
            }
            extList.forEach(groupExtInfo -> kvConf.remove(InlongConstants.SORT_JOB_ID));
            saveInfo(streamInfo, InlongConstants.SORT_JOB_ID, flinkInfo.getJobId(), extList);
            flinkOperation.pollJobStatus(flinkInfo, JobStatus.RUNNING);
        }
        return ListenerResult.success();
    }

    /**
     * Save stream ext info into list.
     */
    private void saveInfo(InlongStreamInfo streamInfo, String keyName, String keyValue,
            List<InlongStreamExtInfo> extInfoList) {
        InlongStreamExtInfo extInfo = new InlongStreamExtInfo();
        extInfo.setInlongGroupId(streamInfo.getInlongGroupId());
        extInfo.setInlongStreamId(streamInfo.getInlongStreamId());
        extInfo.setKeyName(keyName);
        extInfo.setKeyValue(keyValue);
        extInfoList.add(extInfo);
    }

}
