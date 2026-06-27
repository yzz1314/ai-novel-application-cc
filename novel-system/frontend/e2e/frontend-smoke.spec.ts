import { expect, Page, test } from '@playwright/test'

const projectId = 'project-e2e'

test.beforeEach(async ({ page }) => {
  await installApiMocks(page)
})

test('renders global workbench pages with mocked backend data', async ({ page }) => {
  await page.goto('/dashboard')
  await expect(page.getByRole('heading', { name: '工作台' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'E2E 长篇创作项目' })).toBeVisible()
  await expect(page.getByText('retrieval_index')).toBeVisible()

  await page.goto('/projects')
  await expect(page.getByText('项目列表')).toBeVisible()
  await expect(page.getByText('E2E 长篇创作项目', { exact: true }).first()).toBeVisible()

  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: '任务中心' })).toBeVisible()
  await expect(page.getByText('task-retrieval')).toBeVisible()

  await page.goto('/models')
  await expect(page.getByRole('heading', { name: '模型配置' })).toBeVisible()
  await expect(page.getByText('Mock 默认模型')).toBeVisible()
})

test('renders project production pages with fixed API fixtures', async ({ page }) => {
  await page.goto(`/projects/${projectId}`)
  await expect(page.getByRole('heading', { name: 'E2E 长篇创作项目' })).toBeVisible()
  await expect(page.getByText('项目进度')).toBeVisible()
  await page.getByRole('tab', { name: /Skills/ }).click()
  await expect(page.getByText('writing_skill')).toBeVisible()

  await page.goto(`/projects/${projectId}/samples`)
  await expect(page.getByText('样本管理')).toBeVisible()
  await expect(page.getByText('样本一')).toBeVisible()

  await page.goto(`/projects/${projectId}/retrieval`)
  await expect(page.getByRole('heading', { name: '检索与上下文' })).toBeVisible()
  await expect(page.getByText('检索质量评估')).toBeVisible()
  await expect(page.getByText('ctx-1')).toBeVisible()

  await page.goto(`/projects/${projectId}/artifacts`)
  await expect(page.getByRole('heading', { name: '产物管理' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'outline.json', exact: true })).toBeVisible()
})

async function installApiMocks(page: Page) {
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api/, '')
    const method = request.method()

    if (method !== 'GET') {
      return route.fulfill({ json: { id: 'task-mock', status: 'PENDING' } })
    }

    const json = fixtureFor(path)
    if (json === undefined) {
      return route.fulfill({
        status: 404,
        json: { message: `No E2E fixture for ${path}` },
      })
    }
    return route.fulfill({ json })
  })
}

function fixtureFor(path: string): unknown {
  if (path === '/dashboard') return dashboardFixture()
  if (path === '/projects') return [projectFixture()]
  if (path === `/projects/${projectId}`) return projectFixture()
  if (path === '/tasks' || path === `/projects/${projectId}/tasks`) return taskFixtures()
  if (path === '/model-profiles') return modelProfilesFixture()
  if (path === '/model-profiles/default') return modelProfilesFixture()[0]
  if (path === '/model-profiles/versions') return []
  if (path === `/projects/${projectId}/samples`) return sampleFixtures()
  if (path === `/projects/${projectId}/books`) return bookFixtures()
  if (path === `/projects/${projectId}/books/default/soul`) return { content: 'Project Soul: 固定主旨与成长线。' }
  if (path === `/projects/${projectId}/skills`) return skillFixtures()
  if (path === `/projects/${projectId}/skills/conflicts`) return { conflictCount: 0, conflicts: [] }
  if (path === `/projects/${projectId}/analysis/status`) return analysisStatusFixture()
  if (path === `/projects/${projectId}/retrieval`) return retrievalFixture()
  if (path === `/projects/${projectId}/artifacts`) return artifactOverviewFixture()
  if (path === `/projects/${projectId}/artifacts/list`) return artifactListFixture()
  return undefined
}

function projectFixture() {
  return {
    id: projectId,
    name: 'E2E 长篇创作项目',
    projectName: 'E2E 长篇创作项目',
    description: '固定浏览器验收项目',
    genre: '玄幻',
    sampleGroupType: 'SAME_GENRE',
    status: 'WRITING',
    createdAt: '2026-06-26T10:00:00',
    updatedAt: '2026-06-26T10:30:00',
    sampleCount: 2,
  }
}

function taskFixtures() {
  return [
    {
      id: 'task-retrieval',
      projectId,
      taskType: 'retrieval_index',
      agentName: 'retrieval_index',
      status: 'SUCCESS',
      retryCount: 0,
      createdAt: '2026-06-26T10:20:00',
      finishedAt: '2026-06-26T10:21:00',
      metrics: { duration_ms: 1234, model_profile_id: 'mock-default' },
      progress: { percent: 100, label: 'complete' },
    },
    {
      id: 'task-workflow-approval',
      projectId,
      taskType: 'chapter_pipeline',
      agentName: 'workflow',
      status: 'PARTIAL',
      retryCount: 0,
      createdAt: '2026-06-26T10:10:00',
      checkpointRef: 'checkpoint/workflow.json',
      result: {
        waiting_for_human: {
          node_id: 'chapter_review',
          prompt: '请确认章节质量',
        },
      },
      progress: { percent: 70, label: 'waiting for approval' },
    },
  ]
}

function dashboardFixture() {
  return {
    generatedAt: '2026-06-26T10:31:00',
    stats: {
      totalProjects: 1,
      activeProjects: 1,
      archivedProjects: 0,
      totalSamples: 2,
      analyzedSamples: 2,
      totalChapters: 3,
      draftChapters: 2,
      finalChapters: 1,
      totalTasks: 2,
    },
    taskSummary: {
      PENDING: 0,
      RUNNING: 0,
      PARTIAL: 1,
      SUCCESS: 1,
      FAILED: 0,
      CANCELLED: 0,
    },
    healthSummary: {
      status: 'WAITING_APPROVAL',
      message: '有工作流等待人工确认',
      activeTasks: 0,
      failedTasks: 0,
      waitingApprovals: 1,
      successRate: 50,
    },
    serviceStatus: {
      java: { status: 'UP', service: 'novel-system-java' },
      python: { status: 'UP', service: 'python-ai-service' },
    },
    workflowSummary: [
      {
        project: projectFixture(),
        samples: 2,
        analyzedSamples: 2,
        skills: 1,
        outlines: 1,
        draftChapters: 2,
        finalChapters: 1,
        memoryArtifacts: 1,
        graphArtifacts: 1,
        retrievalArtifacts: 1,
        tasks: 2,
        failedTasks: 0,
        progress: 100,
      },
    ],
    blockedProjects: [],
    nextActions: [
      {
        title: '审批工作流',
        target: 'tasks',
        description: '章节工作流等待人工确认。',
      },
    ],
    recentProjects: [
      {
        project: projectFixture(),
        sampleCount: 2,
        chapterCount: 3,
        taskCount: 2,
        failedTaskCount: 0,
      },
    ],
    recentTasks: taskFixtures(),
  }
}

function modelProfilesFixture() {
  return [
    {
      profileId: 'mock-default',
      profileName: 'Mock 默认模型',
      description: '离线验收用模型配置',
      enabled: true,
      updatedAt: '2026-06-26T10:00:00',
      mainModel: {
        provider: 'mock',
        model: 'mock-local',
        mock: true,
        hasApiKey: false,
      },
      fastModel: {
        provider: 'mock',
        model: 'mock-fast',
        mock: true,
        hasApiKey: false,
      },
    },
  ]
}

function sampleFixtures() {
  return [
    {
      id: 'sample-1',
      sampleId: 'sample-1',
      title: '样本一',
      fileName: 'sample-1.txt',
      status: 'ANALYZED',
      createdAt: '2026-06-26T09:00:00',
      totalChars: 12000,
      totalChapters: 8,
    },
    {
      id: 'sample-2',
      sampleId: 'sample-2',
      title: '样本二',
      fileName: 'sample-2.txt',
      status: 'ANALYZED',
      createdAt: '2026-06-26T09:10:00',
      totalChars: 16000,
      totalChapters: 9,
    },
  ]
}

function analysisStatusFixture() {
  return {
    sampleCount: 2,
    analyzedSamples: 2,
    runningTasks: 0,
    hasCrossBookReport: true,
    samples: [
      {
        sampleId: 'sample-1',
        status: 'ANALYZED',
        totalChars: 12000,
        totalChapters: 8,
        chunkCount: 4,
        analysisCount: 4,
        hasBookReport: true,
      },
      {
        sampleId: 'sample-2',
        status: 'ANALYZED',
        totalChars: 16000,
        totalChapters: 9,
        chunkCount: 5,
        analysisCount: 5,
        hasBookReport: true,
      },
    ],
  }
}

function skillFixtures() {
  return [
    {
      name: 'writing_skill',
      title: '写作风格 Skill',
      type: 'writing',
      enabled: true,
      qualityStatus: 'passed',
      qualityScore: 88,
      approvalStatus: 'approved',
      priority: 80,
      sourceTrace: [{ type: 'sample', name: '样本一' }],
      evidenceItems: [{ type: 'quote', text: '固定证据' }],
    },
  ]
}

function bookFixtures() {
  return [
    {
      bookId: 'default',
      bookTitle: '默认书籍',
      genre: '玄幻',
      totalVolumes: 1,
      totalChapters: 3,
      outlinePath: 'novel/outline/outline.json',
      projectSoulPath: 'novel/outline/project_soul.md',
    },
  ]
}

function retrievalFixture() {
  return {
    config: {
      use_keyword: true,
      use_vector: true,
      use_graph: true,
      use_rerank: true,
      top_k: 12,
      max_context_chars: 6000,
      path: 'indexes/retrieval_config.json',
      exists: true,
    },
    indexes: {
      bm25: {
        indexType: 'bm25',
        exists: true,
        engine: 'keyword',
        document_count: 12,
        updatedAt: '2026-06-26T10:00:00',
        path: 'indexes/bm25/summary.json',
      },
      vector: {
        indexType: 'vector',
        exists: true,
        engine: 'hash-vector',
        document_count: 12,
        updatedAt: '2026-06-26T10:00:00',
        path: 'indexes/vector/summary.json',
      },
      hybrid: {
        indexType: 'hybrid',
        exists: true,
        engine: 'rrf',
        document_count: 12,
        updatedAt: '2026-06-26T10:00:00',
        path: 'indexes/hybrid/summary.json',
      },
    },
    contextPacks: [
      {
        id: 'ctx-1',
        bookId: 'default',
        volumeNumber: 1,
        chapterNumber: 1,
        sources: {
          documents_indexed: 12,
          keyword_results: 4,
          vector_results: 4,
          reranked_results: 6,
        },
        qualityEvaluation: { status: 'good', score: 86 },
        citationBudget: { usage: { context_utilization: 0.52 } },
        updatedAt: '2026-06-26T10:05:00',
      },
    ],
    latestTasks: [taskFixtures()[0]],
    qualityReport: {
      exists: true,
      status: 'good',
      score: 86,
      checks: [
        {
          key: 'coverage',
          title: '覆盖率',
          status: 'pass',
          message: '上下文覆盖正常',
          recommendation: '保持',
        },
      ],
      warnings: [],
      recommendations: [],
      coverage: { contextPackCount: 1 },
      path: 'indexes/retrieval_quality_report.json',
    },
  }
}

function artifactOverviewFixture() {
  return {
    categories: [
      { key: 'novel', title: '正文与大纲', count: 1, bytes: 2048 },
      { key: 'indexes', title: '索引', count: 1, bytes: 1024 },
    ],
  }
}

function artifactListFixture() {
  return {
    items: [
      {
        category: 'novel',
        name: 'outline.json',
        path: 'novel/outline/outline.json',
        extension: '.json',
        size: 2048,
        updatedAt: '2026-06-26T10:00:00',
        previewable: true,
        sensitive: false,
      },
      {
        category: 'indexes',
        name: 'retrieval_config.json',
        path: 'indexes/retrieval_config.json',
        extension: '.json',
        size: 1024,
        updatedAt: '2026-06-26T10:01:00',
        previewable: true,
        sensitive: false,
      },
    ],
  }
}
