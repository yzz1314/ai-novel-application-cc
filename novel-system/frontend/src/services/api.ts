import axios from 'axios'
import { message } from 'antd'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/api',
  timeout: 60000,
})

api.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const errorMessage = error.response?.data?.message || error.message || '请求失败'
    message.error(errorMessage)
    return Promise.reject(error)
  }
)

const request = <T = any>(promise: Promise<any>): Promise<T> => promise as Promise<T>

export const projectApi = {
  getList: (params?: any) => request<any[]>(api.get('/projects', { params })),
  create: (data: any) => request(api.post('/projects', data)),
  getDetail: (projectId: string) => request(api.get(`/projects/${projectId}`)),
  update: (projectId: string, data: any) => request(api.put(`/projects/${projectId}`, data)),
  delete: (projectId: string) => request(api.delete(`/projects/${projectId}`)),
}

export const modelProfileApi = {
  getList: () => request<any[]>(api.get('/model-profiles')),
  getDefault: () => request(api.get('/model-profiles/default')),
  create: (data: any) => request(api.post('/model-profiles', data || {})),
  get: (profileId: string) => request(api.get(`/model-profiles/${profileId}`)),
  update: (profileId: string, data: any) => request(api.patch(`/model-profiles/${profileId}`, data || {})),
  delete: (profileId: string) => request(api.delete(`/model-profiles/${profileId}`)),
  setDefault: (profileId: string) => request(api.post(`/model-profiles/${profileId}/default`)),
  test: (profileId: string, data?: any) => request(api.post(`/model-profiles/${profileId}/test`, data || {})),
}

export const sampleApi = {
  upload: (projectId: string, file: File, data: any) => {
    const formData = new FormData()
    formData.append('file', file)
    Object.keys(data || {}).forEach((key) => {
      const value = data[key]
      if (value !== undefined && value !== null) {
        formData.append(key, String(value))
      }
    })
    return request(
      api.post(`/projects/${projectId}/samples`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
    )
  },
  getList: (projectId: string) => request<any[]>(api.get(`/projects/${projectId}/samples`)),
  getDetail: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/samples/${sampleId}`)),
  delete: (projectId: string, sampleId: string) =>
    request(api.delete(`/projects/${projectId}/samples/${sampleId}`)),
}

export const taskApi = {
  execute: (projectId: string, data: { agentName: string; config?: any; inputRefs?: any }) =>
    request(api.post(`/projects/${projectId}/tasks/execute`, data)),
  getStatus: (projectId: string, taskId: string) =>
    request(api.get(`/tasks/${taskId}`, { params: { projectId } })),
  getList: (projectId: string) => request<any[]>(api.get('/tasks', { params: { projectId } })),
  retry: (taskId: string) => request(api.post(`/tasks/${taskId}/retry`)),
  cancel: (taskId: string) => request(api.post(`/tasks/${taskId}/cancel`)),
  getLogs: (taskId: string) => request(api.get(`/tasks/${taskId}/logs`)),
}

export const skillsApi = {
  generate: (projectId: string, config: any) =>
    taskApi.execute(projectId, { agentName: 'skill_generation', config }),
  getList: (projectId: string) => request<any[]>(api.get(`/projects/${projectId}/skills`)),
  getContent: (projectId: string, skillName: string) =>
    request(api.get(`/projects/${projectId}/skills/${skillName}`)),
  getEnabled: (projectId: string) => request(api.get(`/projects/${projectId}/skills/enabled`)),
  getEnabledVersions: (projectId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/skills/enabled/versions`)),
  restoreEnabledVersion: (projectId: string, versionId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/skills/enabled/versions/${versionId}/restore`, data || {})),
  getConflicts: (projectId: string) => request(api.get(`/projects/${projectId}/skills/conflicts`)),
  update: (projectId: string, skillName: string, data: any) =>
    request(api.patch(`/projects/${projectId}/skills/${skillName}`, data || {})),
  updateConfig: (projectId: string, skillName: string, data: any) =>
    request(api.patch(`/projects/${projectId}/skills/${skillName}/config`, data || {})),
  getVersions: (projectId: string, skillName: string) =>
    request<any[]>(api.get(`/projects/${projectId}/skills/${skillName}/versions`)),
  getVersion: (projectId: string, skillName: string, versionId: string) =>
    request(api.get(`/projects/${projectId}/skills/${skillName}/versions/${versionId}`)),
  restoreVersion: (projectId: string, skillName: string, versionId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/versions/${versionId}/restore`, data || {})),
  checkQuality: (projectId: string, skillName: string, data?: any) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/quality-check`, data || {})),
  approve: (projectId: string, skillName: string, data?: any) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/approve`, data || {})),
  reject: (projectId: string, skillName: string, data?: any) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/reject`, data || {})),
  enable: (projectId: string, skillName: string) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/enable`)),
  disable: (projectId: string, skillName: string) =>
    request(api.post(`/projects/${projectId}/skills/${skillName}/disable`)),
}

export const bookApi = {
  getList: (projectId: string) => request<any[]>(api.get(`/projects/${projectId}/books`)),
  getSoul: (projectId: string, bookId = 'default') =>
    request(api.get(`/projects/${projectId}/books/${bookId}/soul`)),
}

export const outlineApi = {
  generate: (projectId: string, config: any) =>
    taskApi.execute(projectId, {
      agentName: 'outline_generation',
      config: { project_id: projectId, ...config },
    }),
  get: (projectId: string, bookId: string) =>
    request(api.get(`/projects/${projectId}/books/${bookId}/outline`)),
  update: (projectId: string, bookId: string, data: any) =>
    request(api.patch(`/projects/${projectId}/books/${bookId}/outline`, data || {})),
  review: (projectId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/outline/review`, data || {})),
  getReviews: (projectId: string, bookId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/books/${bookId}/outline/reviews`)),
}

export const chapterApi = {
  generate: (projectId: string, config: any) =>
    taskApi.execute(projectId, {
      agentName: 'chapter_writing',
      config: { project_id: projectId, ...config },
    }),
  getList: (projectId: string, bookId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/books/${bookId}/chapters`)),
  getContent: (projectId: string, bookId: string, volumeNumber: number, chapterNumber: number) =>
    request(
      api.get(`/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}`)
    ),
  finalize: (
    projectId: string,
    bookId: string,
    volumeNumber: number,
    chapterNumber: number,
    data?: any
  ) =>
    request(
      api.post(
        `/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/finalize`,
        data || {}
      )
    ),
  revise: (
    projectId: string,
    bookId: string,
    volumeNumber: number,
    chapterNumber: number,
    data?: any
  ) =>
    request(
      api.post(
        `/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/revision`,
        data || {}
      )
    ),
  getVersions: (projectId: string, bookId: string, volumeNumber: number, chapterNumber: number) =>
    request<any[]>(
      api.get(`/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/versions`)
    ),
  restoreVersion: (
    projectId: string,
    bookId: string,
    volumeNumber: number,
    chapterNumber: number,
    versionId: string,
    data?: any
  ) =>
    request(
      api.post(
        `/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/versions/${versionId}/restore`,
        data || {}
      )
    ),
  review: (
    projectId: string,
    bookId: string,
    volumeNumber: number,
    chapterNumber: number,
    data?: any
  ) =>
    request(
      api.post(
        `/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/review`,
        data || {}
      )
    ),
  update: (
    projectId: string,
    bookId: string,
    volumeNumber: number,
    chapterNumber: number,
    data?: any
  ) =>
    request(
      api.patch(
        `/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}`,
        data || {}
      )
    ),
  getReviews: (projectId: string, bookId: string, volumeNumber: number, chapterNumber: number) =>
    request<any[]>(
      api.get(`/projects/${projectId}/books/${bookId}/chapters/${volumeNumber}/${chapterNumber}/reviews`)
    ),
}

export const memoryApi = {
  extract: (projectId: string, config: any) =>
    taskApi.execute(projectId, { agentName: 'memory_extraction', config }),
  getOverview: (projectId: string) => request(api.get(`/projects/${projectId}/memory`)),
  getType: (projectId: string, type: string) =>
    request(api.get(`/projects/${projectId}/memory/${type}`)),
  getCharacters: (projectId: string, _bookId?: string) =>
    request<any[]>(api.get(`/projects/${projectId}/memory/characters/list`)),
  getWorldSettings: (projectId: string, _bookId?: string) =>
    request<any[]>(api.get(`/projects/${projectId}/memory/world-settings/list`)),
  getTimeline: (projectId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/memory/timeline/list`)),
  getSnapshots: (projectId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/memory/snapshots/list`)),
  getSnapshot: (projectId: string, snapshotId: string) =>
    request(api.get(`/projects/${projectId}/memory/snapshots/${snapshotId}`)),
  rebuild: (projectId: string, config: any) =>
    request(api.post(`/projects/${projectId}/memory/rebuild`, config)),
  getDb: (projectId: string) =>
    request(api.get(`/projects/${projectId}/memory/db`)),
  syncDb: (projectId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/memory/sync`, data || {})),
  checkContinuity: (projectId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/memory/continuity/check`, data || {})),
  getContinuityReports: (projectId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/memory/continuity/reports`)),
  getContinuityReport: (projectId: string, reportId: string) =>
    request(api.get(`/projects/${projectId}/memory/continuity/reports/${reportId}`)),
}

export const graphApi = {
  build: (projectId: string, config: any) =>
    taskApi.execute(projectId, {
      agentName: 'graph_build',
      config: { project_id: projectId, ...config },
    }),
  get: (projectId: string, bookId: string) =>
    request(api.get(`/projects/${projectId}/books/${bookId}/graph`)),
  query: (projectId: string, bookId: string, data: any) =>
    request(api.post(`/projects/${projectId}/books/${bookId}/graph/query`, data)),
  rebuild: (projectId: string, bookId: string, data: any) =>
    request(api.post(`/projects/${projectId}/books/${bookId}/graph/rebuild`, data)),
  export: (projectId: string, bookId: string, format: string) =>
    request<Blob>(
      api.get(`/projects/${projectId}/books/${bookId}/graph/export`, {
        params: { format },
        responseType: 'blob',
      })
    ),
  getDb: (projectId: string, bookId: string) =>
    request(api.get(`/projects/${projectId}/books/${bookId}/graph/db`)),
  syncDb: (projectId: string, bookId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/books/${bookId}/graph/sync`, data || {})),
}

export const retrievalApi = {
  getOverview: (projectId: string) => request(api.get(`/projects/${projectId}/retrieval`)),
  getIndexes: (projectId: string) => request(api.get(`/projects/${projectId}/retrieval/indexes`)),
  getIndex: (projectId: string, indexType: string) =>
    request(api.get(`/projects/${projectId}/retrieval/indexes/${indexType}`)),
  getConfig: (projectId: string) => request(api.get(`/projects/${projectId}/retrieval/config`)),
  updateConfig: (projectId: string, data: any) =>
    request(api.patch(`/projects/${projectId}/retrieval/config`, data || {})),
  rebuild: (projectId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/retrieval/rebuild`, data || {})),
  getContextPacks: (projectId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/retrieval/context-packs`)),
  getContextPack: (projectId: string, contextPackId: string) =>
    request(api.get(`/projects/${projectId}/retrieval/context-packs/${contextPackId}`)),
  getDb: (projectId: string) =>
    request(api.get(`/projects/${projectId}/retrieval/db`)),
  syncDb: (projectId: string, data?: any) =>
    request(api.post(`/projects/${projectId}/retrieval/sync`, data || {})),
}

export const artifactApi = {
  getOverview: (projectId: string) => request(api.get(`/projects/${projectId}/artifacts`)),
  list: (projectId: string, params?: any) =>
    request(api.get(`/projects/${projectId}/artifacts/list`, { params })),
  view: (projectId: string, path: string) =>
    request(api.get(`/projects/${projectId}/artifacts/view`, { params: { path } })),
  download: (projectId: string, path: string) =>
    request<Blob>(
      api.get(`/projects/${projectId}/artifacts/download`, {
        params: { path },
        responseType: 'blob',
      })
    ),
}

export const analysisApi = {
  getStatus: (projectId: string) => request(api.get(`/projects/${projectId}/analysis/status`)),
  getReport: (projectId: string) => request(api.get(`/projects/${projectId}/analysis/report`)),
  getCrossBookReport: (projectId: string) =>
    request(api.get(`/projects/${projectId}/analysis/cross-book`)),
  getSampleArtifacts: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/artifacts`)),
  getSampleManifest: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/manifest`)),
  getChunks: (projectId: string, sampleId: string) =>
    request<any[]>(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/chunks`)),
  getChunk: (projectId: string, sampleId: string, chunkId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/chunks/${chunkId}`)),
  getSampleAnalysis: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/analysis`)),
  getChunkAnalysis: (projectId: string, sampleId: string, chunkId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/chunks/${chunkId}/analysis`)),
  getCoverage: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/coverage`)),
  checkCoverage: (projectId: string, sampleId: string) =>
    request(api.post(`/projects/${projectId}/analysis/samples/${sampleId}/coverage/check`)),
  getBookReport: (projectId: string, sampleId: string) =>
    request(api.get(`/projects/${projectId}/analysis/samples/${sampleId}/book-report`)),
  importSample: (projectId: string, sampleId: string) =>
    request(api.post(`/projects/${projectId}/tasks/execute`, {
      agentName: 'sample_import',
      inputRefs: { sample_id: sampleId },
      config: { sample_id: sampleId },
    })),
  analyzeFullText: (projectId: string, sampleId: string) =>
    request(api.post(`/projects/${projectId}/tasks/execute`, {
      agentName: 'full_text_analysis',
      inputRefs: { sample_id: sampleId },
      config: { sample_id: sampleId },
    })),
  summarizeBook: (projectId: string, sampleId: string) =>
    request(api.post(`/projects/${projectId}/tasks/execute`, {
      agentName: 'book_summary',
      inputRefs: { sample_id: sampleId },
      config: { sample_id: sampleId },
    })),
  synthesizeBooks: (projectId: string, sampleIds: string[]) =>
    request(api.post(`/projects/${projectId}/tasks/execute`, {
      agentName: 'cross_book_synthesis',
      inputRefs: { sample_ids: sampleIds },
      config: { sample_ids: sampleIds },
    })),
}

export default api
