/*
 * Copyright (c) 2015-2020, www.dibo.ltd (service@dibo.ltd).
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.diboot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.diboot.core.config.Cons;
import com.diboot.core.exception.BusinessException;
import com.diboot.core.service.impl.BaseServiceImpl;
import com.diboot.core.util.BeanUtils;
import com.diboot.core.util.ContextHelper;
import com.diboot.core.util.JSON;
import com.diboot.core.util.V;
import com.diboot.core.vo.Status;
import com.diboot.scheduler.entity.ScheduleJob;
import com.diboot.scheduler.job.anno.BindJob;
import com.diboot.scheduler.mapper.ScheduleJobMapper;
import com.diboot.scheduler.service.ScheduleJobService;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.*;

/**
 * 定时任务Job定义Service实现
 *
 * @author mazc@dibo.ltd
 * @version 2.2
 * @date 2020-11-27
 */
@Service
@Slf4j
public class ScheduleJobServiceImpl extends BaseServiceImpl<ScheduleJobMapper, ScheduleJob> implements ScheduleJobService {

    // 任务的缓存
    public static final List<Map<String, Object>> CACHE_JOB = new ArrayList<>();

    @Autowired
    private QuartzSchedulerService quartzSchedulerService;

    @Override
    public boolean createEntity(ScheduleJob entity) {
        setJobClass(entity);
        quartzSchedulerService.addJob(entity);
        return super.createEntity(entity);
    }

    @Override
    public boolean updateEntity(ScheduleJob entity) {
        ScheduleJob oldJob = getEntity(entity.getId());
        // 定时不同
        if (V.notEquals(oldJob.getCron(), entity.getCron())) {
            quartzSchedulerService.updateJobCron(oldJob.getJobKey(), entity.getCron());
        }
        return super.updateEntity(entity);
    }

    @Override
    public boolean deleteEntity(Serializable jobId) {
        String jobKeyObj = getValueOfField(ScheduleJob::getId, jobId, ScheduleJob::getJobKey);
        quartzSchedulerService.deleteJob(jobKeyObj);
        return super.deleteEntity(jobId);
    }

    @Override
    public boolean executeOnceJob(Long jobId) {
        String jobKeyObj = getValueOfField(ScheduleJob::getId, jobId, ScheduleJob::getJobKey);
        // 如果已经存在job，那么直接恢复，否则添加job
        if (!quartzSchedulerService.existJob(jobKeyObj)) {
            ScheduleJob entity = this.getEntity(jobId);
            if (V.isEmpty(entity)) {
                throw new BusinessException(Status.FAIL_OPERATION, "当前任务无效！");
            }
            setJobClass(entity);
            quartzSchedulerService.addJobExecuteOnce(entity);
        } else {
            quartzSchedulerService.runJob(jobKeyObj);
        }

        return true;
    }

    @Override
    public boolean changeScheduleJobStatus(Long jobId, String jobStatus) {
        boolean success = this.update(
                Wrappers.<ScheduleJob>lambdaUpdate()
                        .set(ScheduleJob::getJobStatus, jobStatus)
                        .eq(ScheduleJob::getId, jobId)
        );
        if (!success) {
            throw new BusinessException(Status.FAIL_OPERATION, "更新状态失败！");
        }
        String jobKeyObj = getValueOfField(ScheduleJob::getId, jobId, ScheduleJob::getJobKey);
        // 恢复
        if (Cons.ENABLE_STATUS.A.name().equals(jobStatus)) {
            // 如果已经存在job，那么直接恢复，否则添加job
            if (quartzSchedulerService.existJob(jobKeyObj)) {
                quartzSchedulerService.resumeJob((String) jobKeyObj);
            } else {
                ScheduleJob entity = this.getEntity(jobId);
                if (V.isEmpty(entity)) {
                    throw new BusinessException(Status.FAIL_OPERATION, "当前任务无效！");
                }
                setJobClass(entity);
                quartzSchedulerService.addJob(entity);
            }
        }
        // 停止
        else if (Cons.ENABLE_STATUS.I.name().equals(jobStatus)) {
            quartzSchedulerService.pauseJob((String) jobKeyObj);
        } else {
            log.warn("无效的action参数: {}", jobStatus);
        }

        return true;
    }

    @Override
    public List<Map<String, Object>> getAllJobs() throws Exception {
        if (V.notEmpty(CACHE_JOB)) {
            return CACHE_JOB;
        }
        // 获取所有被com.diboot.scheduler.job.anno.BindJob注解的job
        List<Object> jobByScheduledAnnotationList = ContextHelper.getBeansByAnnotation(BindJob.class);
        if (V.isEmpty(jobByScheduledAnnotationList)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object job : jobByScheduledAnnotationList) {
            if (!(job instanceof Job)) {
                log.warn("无效的job任务: {}", job.getClass());
                continue;
            }
            Map<String, Object> temp = new HashMap<>(8);
            Class targetClass = BeanUtils.getTargetClass(job);

            BindJob annotation = (BindJob) targetClass.getAnnotation(BindJob.class);
            temp.put("jobCron", annotation.cron());
            temp.put("jobName", annotation.name());
            temp.put("jobClass", targetClass);
            String paramJsonExample = "";
            if (V.notEmpty(annotation.paramJson())) {
                paramJsonExample = annotation.paramJson();
            } else if (!Object.class.getTypeName().equals(annotation.paramClass().getTypeName())) {
                try {
                    paramJsonExample = JSON.stringify(annotation.paramClass().newInstance());
                } catch (Exception e) {
                    log.error("job任务：{}, Scheduled#paramClass参数任务无效，建议使用Scheduled#paramJson参数替换！", job.getClass());
                }
            }
            temp.put("paramJsonExample", paramJsonExample);
            result.add(temp);
        }
        CACHE_JOB.addAll(result);
        return result;
    }

    /**
     * 设置ScheduleJob#JobClass
     * @param entity
     */
    private void setJobClass(ScheduleJob entity){
        String jobName = V.isEmpty(entity.getJobName()) ? "" : entity.getJobName();
        if (V.isEmpty(CACHE_JOB)) {
            try {
                getAllJobs();
            } catch (Exception e) {
                throw new BusinessException(Status.FAIL_OPERATION, "定时任务加载失败！");
            }

        }
        Map<String, Object> jobMap = CACHE_JOB.stream()
                .filter(s -> jobName.equals(String.valueOf(s.get("jobName"))))
                .findFirst()
                .get();
        if (jobMap == null) {
            throw new BusinessException(Status.FAIL_INVALID_PARAM, "非法的定时任务名称!");
        }
        entity.setJobClass((Class<? extends Job>) jobMap.get("jobClass"));
    }

}