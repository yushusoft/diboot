<script setup lang="ts" name="DiInput">
import { Plus, Upload as UploadIcon } from '@element-plus/icons-vue'
import type { FormItem, Select, Upload } from './type'
import type { UploadRawFile, UploadFile, FormItemRule } from 'element-plus'

const props = withDefaults(
  defineProps<{
    config: FormItem
    modelValue?: unknown
    disabled?: boolean
    baseApi?: string
    getId?: () => string | undefined
    relatedDatas?: LabelValue<string | never>[]
    lazyLoading?: boolean
    fileList?: FileRecord[]
    lazyLoad?: (parentId: string) => Promise<LabelValue[]>
    teleported?: boolean
    formPropPrefix?: string
  }>(),
  {
    modelValue: undefined,
    baseApi: undefined,
    getId: undefined,
    relatedDatas: undefined,
    fileList: undefined,
    lazyLoad: undefined,
    teleported: true,
    formPropPrefix: ''
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value?: unknown): void
  (e: 'change', value?: unknown): void
  (e: 'remoteFilter', value?: string): void
  (e: 'preview', accessUrl: string, isImage: boolean): void
}>()

const instance = getCurrentInstance()

const value = ref(
  props.config.type === 'input-number' && props.modelValue ? Number(`${props.modelValue}`) : props.modelValue
)
watch(
  value,
  value => {
    emit('update:modelValue', value)
    instance?.proxy?.$forceUpdate()
  },
  { deep: true }
)
watch(
  () => props.modelValue,
  val => (value.value = props.config.type === 'input-number' && val ? Number(val as string) : val)
)

const requiredRule = {
  required: true,
  message: '不能为空',
  ...(props.config.type === 'input-number' || props.config.type === 'checkbox' || (props.config as Select).multiple
    ? {}
    : { whitespace: true })
}
const checkUniqueRule = {
  validator: (rule: unknown, value: unknown, callback: (error?: string | Error) => void) => {
    if (value) {
      api
        .get(`${props.baseApi}/check-unique`, {
          id: props.getId ? props.getId() : undefined,
          field: props.config.prop,
          value
        })
        .then(() => {
          callback()
        })
        .catch(err => {
          callback(err.msg || err)
        })
    } else callback()
  },
  trigger: 'blur'
}

const rules = (
  (props.config.required && props.config.unique
    ? [requiredRule, checkUniqueRule]
    : props.config.required
    ? [requiredRule]
    : props.config.unique
    ? [checkUniqueRule]
    : []) as FormItemRule[]
).concat(...(props.config.rules ?? []))

const handleChange = (value?: unknown) => emit('change', value)

const remoteFilter = (value?: unknown) => emit('remoteFilter', value as string | undefined)

const lazyLoad = ({ data }: { data: LabelValue }, resolve: (data: LabelValue[]) => void) =>
  props.lazyLoad ? props.lazyLoad(data.value).then(list => resolve(list)) : resolve([])

const DEFAULT_DATE_FORMAT: Record<string, string> = { date: 'YYYY-MM-DD', datetime: 'YYYY-MM-DD HH:mm:ss' }
const getDateFormtDef = (type: string) => DEFAULT_DATE_FORMAT[type]

const { action, httpRequest, fileList, onSuccess, onRemove } = useUploadFile(
  fileIds => (value.value = fileIds),
  () => props.fileList
)

const previewFile = (file: UploadFile) =>
  emit(
    'preview',
    file.accessUrl as string,
    ['picture-card', 'picture'].includes((props.config as Upload).listType as string)
  )

const beforeUpload = (rawFile: UploadRawFile) => {
  const fileConfig: Upload = props.config as any
  const accept = convert2accept(fileConfig?.accept)
  if (accept && !accept.split(',').includes(rawFile.name.substring(rawFile.name.lastIndexOf('.')))) {
    ElMessage.error(`请上传${accept.replace(/,/g, '/')}格式的文件！`)
    return false
  }
  if (fileConfig.size && rawFile.size / 1024 / 1024 > fileConfig.size) {
    ElMessage.error(`文件过大，超出了限制${fileConfig.size}MB，请调整您的文件!`)
    return false
  }
  return true
}
// 获取可用的accept列表
const convert2accept = (accept?: string) => {
  if (!accept) {
    return undefined
  }
  return accept
    .split(',')
    .map((item: string) => {
      if (item.indexOf('.') !== 0) {
        return `.${item}`
      } else {
        return item
      }
    })
    .join(',')
}
</script>

<template>
  <el-form-item :prop="`${formPropPrefix}${config.prop}`" :label="config.label" :rules="rules">
    <el-input
      v-if="config.type === 'input'"
      v-model="value"
      :placeholder="config.placeholder ?? '请输入'"
      clearable
      :maxlength="config.maxlength as number"
      :show-word-limit="!!config.maxlength"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <el-input
      v-else-if="config.type === 'textarea'"
      v-model="value"
      :placeholder="config.placeholder"
      clearable
      type="textarea"
      :autosize="config.autosize as boolean"
      :maxlength="config.maxlength as number"
      :show-word-limit="!!config.maxlength"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <template v-else-if="config.type === 'rich'">
      <rich-read
        v-if="config.disabled || disabled"
        :value="`${value ?? ''}`"
        :style="{ flex: 1, height: config.height }"
      />
      <rich-editor
        v-else
        v-model="value"
        :placeholder="config.placeholder"
        :mode="config.mode"
        :style="{ height: config.height }"
      />
    </template>
    <el-input-number
      v-if="config.type === 'input-number'"
      v-model="value"
      :placeholder="config.placeholder"
      :min="config.min"
      :max="config.max"
      :precision="config.precision"
      :step-strictly="!config.precision"
      :controls="config.controls === false ? false : undefined"
      :controls-position="config.controls === 'right' ? 'right' : ''"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <number-range
      v-if="config.type === 'input-number-range'"
      v-model="value"
      :min="config.min"
      :max="config.max"
      :precision="config.precision"
      :controls="config.controls"
      @change="handleChange"
    />
    <template v-if="config.type === 'boolean'">
      <el-switch v-if="config.mode === 'switch'" v-model="value" :disabled="config.disabled || disabled" />
      <el-select v-else v-model="value" clearable :disabled="config.disabled || disabled">
        <el-option label="是" :value="true" />
        <el-option label="否" :value="false" />
      </el-select>
    </template>
    <el-select
      v-if="config.type === 'select'"
      v-model="value"
      clearable
      filterable
      :placeholder="config.placeholder"
      :multiple="config.multiple"
      :remote="config.remote"
      :remote-method="config.remote ? remoteFilter : undefined"
      :loading="lazyLoading"
      :disabled="config.disabled || disabled"
      :teleported="teleported"
      @change="handleChange"
    >
      <el-option v-for="item in relatedDatas" :key="item.value" v-bind="item">
        <div v-if="typeof item.ext === 'string'" class="option">
          {{ item.label }}
          <span class="ext">（{{ item.ext }}）</span>
        </div>
      </el-option>
    </el-select>
    <el-cascader
      v-if="config.type === 'cascader'"
      v-model="value"
      :placeholder="config.placeholder"
      clearable
      filterable
      :teleported="teleported"
      :options="relatedDatas"
      :props="{
        emitPath: false,
        lazy: config.lazy,
        lazyLoad: config.lazy ? lazyLoad : undefined,
        multiple: config.multiple,
        checkStrictly: config.checkStrictly
      }"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <el-tree-select
      v-if="config.type === 'tree-select'"
      v-model="value"
      :placeholder="config.placeholder"
      :data="relatedDatas"
      :filterable="!config.lazy"
      :lazy="config.lazy"
      :load="config.lazy ? lazyLoad : undefined"
      :check-strictly="config.checkStrictly"
      :default-expand-all="!config.lazy"
      :multiple="config.multiple"
      :disabled="config.disabled || disabled"
      :teleported="teleported"
      clearable
      @change="handleChange"
    />
    <el-checkbox-group
      v-if="config.type === 'checkbox'"
      v-model="value"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    >
      <el-checkbox v-for="(item, index) in relatedDatas" :key="index" :label="item.value">{{ item.label }}</el-checkbox>
    </el-checkbox-group>
    <el-radio-group
      v-if="config.type === 'radio'"
      v-model="value"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    >
      <el-radio v-for="(item, index) in relatedDatas" :key="index" :label="item.value">{{ item.label }}</el-radio>
    </el-radio-group>
    <di-selector
      v-if="config.type === 'list-selector'"
      v-model="value"
      :tree="config.tree"
      :list="config.list"
      :multiple="config.multiple"
      :data-type="config.dataType"
      :data-label="config.dataLabel"
      :placeholder="config.placeholder"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <el-date-picker
      v-if="
        config.type === 'year' ||
        config.type === 'month' ||
        config.type === 'date' ||
        config.type === 'datetime' ||
        config.type === 'week'
      "
      v-model="value"
      clearable
      :type="config.type"
      :value-format="config.format ? config.format : getDateFormtDef(config.type)"
      :placeholder="config.placeholder"
      :disabled="config.disabled || disabled"
      :teleported="teleported"
      @change="handleChange"
    />
    <el-time-select
      v-if="config.type === 'time'"
      v-model="value"
      clearable
      :placeholder="config.placeholder"
      start="00:00"
      step="00:15"
      end="23:59"
      :disabled="config.disabled || disabled"
      @change="handleChange"
    />
    <date-range
      v-if="['daterange', 'datetimerange'].includes(config.type)"
      v-model="value"
      :type="config.type"
      :teleported="teleported"
      @change="handleChange"
    />
    <el-upload
      v-if="config.type === 'upload'"
      v-model:file-list="fileList"
      :action="action"
      :http-request="httpRequest"
      :on-success="onSuccess"
      :on-remove="onRemove"
      :limit="config.limit"
      :accept="config.accept"
      :list-type="config.listType ?? 'text'"
      :multiple="(config.limit ?? 2) > 1"
      :before-upload="beforeUpload"
      :on-preview="previewFile"
      :disabled="config.disabled || disabled"
      :class="{
        'upload-plus-hide': fileList.length >= (config.limit ?? Number.MAX_VALUE),
        'upload-plus-disabled': config.disabled || disabled
      }"
      style="width: 100%"
    >
      <el-icon v-if="config.listType === 'picture-card'">
        <Plus />
      </el-icon>
      <el-button
        v-else-if="!(fileList.length >= (config.limit ?? Number.MAX_VALUE))"
        :icon="UploadIcon"
        :disabled="config.disabled || disabled"
      >
        上传文件
      </el-button>
      <template #tip>
        <el-button
          v-if="config.listType !== 'picture-card' && fileList.length >= (config.limit ?? Number.MAX_VALUE)"
          :icon="UploadIcon"
          disabled
        >
          上传文件
        </el-button>
        <div v-if="config.placeholder" class="el-upload__tip">
          {{ config.placeholder }}
        </div>
        <div v-else-if="config.limit || config.accept || config.size" class="el-upload__tip">
          可上传<span v-if="config.limit">{{ ' ' }}{{ config.limit }} 个<span v-if="!config.accept">文件</span></span>
          <span v-if="config.accept">类型为 {{ config.accept.replace(/,/g, '/') }} 的文件</span>
          <span v-if="config.size && (config.limit || config.accept)">，</span>
          <span v-if="config.size">单文件需小于 {{ config.size }} MB</span>。
        </div>
      </template>
    </el-upload>
  </el-form-item>
</template>

<style scoped>
.option {
  display: flex;
  justify-content: space-between;

  .ext {
    font-size: var(--el-font-size-extra-small);
    color: var(--el-text-color-secondary);
  }
}

.upload-plus-hide :deep(.el-upload--picture-card) {
  display: none;
}

.upload-plus-disabled :deep(.el-upload--picture-card) {
  cursor: not-allowed;
}
</style>
