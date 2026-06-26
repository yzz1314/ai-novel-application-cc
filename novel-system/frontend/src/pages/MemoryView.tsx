import React, { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  List,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  ClockCircleOutlined,
  CheckCircleOutlined,
  FileTextOutlined,
  ReloadOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { memoryApi } from '../services/api'

const { Paragraph, Text, Title } = Typography
const { Search } = Input

const memoryTypes = [
  { key: 'characters', label: '人物', color: 'blue' },
  { key: 'foreshadowing', label: '伏笔', color: 'purple' },
  { key: 'timeline', label: '时间线', color: 'green' },
  { key: 'relationships', label: '关系', color: 'cyan' },
  { key: 'cognition', label: '认知', color: 'orange' },
  { key: 'canon', label: 'Canon', color: 'red' },
]

const MemoryView: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [loading, setLoading] = useState(false)
  const [overview, setOverview] = useState<any>(null)
  const [memoryDetail, setMemoryDetail] = useState<Record<string, any>>({})
  const [activeType, setActiveType] = useState('characters')
  const [snapshots, setSnapshots] = useState<any[]>([])
  const [continuityReports, setContinuityReports] = useState<any[]>([])
  const [auditReports, setAuditReports] = useState<any[]>([])
  const [snapshotDrawer, setSnapshotDrawer] = useState<any>(null)
  const [continuityDrawer, setContinuityDrawer] = useState<any>(null)
  const [auditDrawer, setAuditDrawer] = useState<any>(null)
  const [searchText, setSearchText] = useState('')
  const [resolutionNotes, setResolutionNotes] = useState<Record<string, string>>({})
  const [continuityForm] = Form.useForm()

  useEffect(() => {
    loadMemories()
  }, [projectId])

  const loadMemories = async () => {
    if (!projectId) return
    try {
      setLoading(true)
      const overviewData = await memoryApi.getOverview(projectId)
      setOverview(overviewData)
      setSnapshots(overviewData?.snapshots || [])
      setContinuityReports(overviewData?.continuityReports || [])
      setAuditReports(overviewData?.auditReports || [])

      const details = await Promise.all(
        memoryTypes.map((type) =>
          memoryApi.getType(projectId, type.key)
            .then((data) => [type.key, data])
            .catch(() => [type.key, null])
        )
      )
      setMemoryDetail(Object.fromEntries(details))
    } catch (error) {
      message.error('加载记忆失败')
    } finally {
      setLoading(false)
    }
  }

  const syncMemoryDb = async () => {
    if (!projectId) return
    try {
      message.loading({ content: '正在同步记忆数据库', key: 'memory-sync' })
      await memoryApi.syncDb(projectId, { book_id: 'default' })
      message.success({ content: '记忆数据库已同步', key: 'memory-sync' })
      await loadMemories()
    } catch (error) {
      message.error({ content: '同步记忆数据库失败', key: 'memory-sync' })
    }
  }

  const openSnapshot = async (snapshot: any) => {
    if (!projectId) return
    try {
      const data = await memoryApi.getSnapshot(projectId, snapshot.id)
      setSnapshotDrawer(data)
    } catch (error) {
      message.error('加载快照失败')
    }
  }

  const runContinuityCheck = async () => {
    if (!projectId) return
    try {
      const values = await continuityForm.validateFields()
      const chapterNumber = values.chapterNumber || 1
      const payload = {
        book_id: values.bookId || 'default',
        volume_number: values.volumeNumber || 1,
        chapter_number: chapterNumber,
        chapter_id: values.chapterId || `chapter_${chapterNumber}`,
        check_characters: true,
        check_settings: true,
        check_timeline: true,
      }
      const task: any = await memoryApi.checkContinuity(projectId, payload)
      message.success(`连续性检查任务已创建：${task.id}`)
      await loadMemories()
    } catch (error) {
      message.error('创建连续性检查失败')
    }
  }

  const runMemoryAudit = async () => {
    if (!projectId) return
    try {
      const task: any = await memoryApi.audit(projectId, { book_id: 'default' })
      message.success(`记忆审计任务已创建：${task.id}`)
      await loadMemories()
    } catch (error) {
      message.error('创建记忆审计失败')
    }
  }

  const openContinuityReport = async (report: any) => {
    if (!projectId) return
    try {
      const data = await memoryApi.getContinuityReport(projectId, report.id)
      setContinuityDrawer(data)
    } catch (error) {
      message.error('加载连续性报告失败')
    }
  }

  const openAuditReport = async (report: any) => {
    if (!projectId) return
    try {
      const data = await memoryApi.getAuditReport(projectId, report.id)
      setAuditDrawer(data)
    } catch (error) {
      message.error('加载记忆审计报告失败')
    }
  }

  const issueKey = (reportType: 'continuity' | 'audit', issueIndex: number) => `${reportType}-${issueIndex}`

  const resolutionStatus = (issue: any) =>
    issue?.resolution_status || issue?.resolutionStatus || issue?.resolution?.status || 'open'

  const resolutionLabel = (status: string) => {
    const normalized = status || 'open'
    if (normalized === 'resolved') return '已解决'
    if (normalized === 'accepted_risk') return '接受风险'
    if (normalized === 'ignored') return '忽略'
    return '待处理'
  }

  const resolutionColor = (status: string) => {
    const normalized = status || 'open'
    if (normalized === 'resolved') return 'success'
    if (normalized === 'accepted_risk') return 'warning'
    if (normalized === 'ignored') return 'default'
    return 'processing'
  }

  const handleResolveIssue = async (
    reportType: 'continuity' | 'audit',
    issueIndex: number,
    status: 'resolved' | 'accepted_risk' | 'ignored' | 'open'
  ) => {
    if (!projectId) return
    const drawer = reportType === 'continuity' ? continuityDrawer : auditDrawer
    if (!drawer?.id) return
    const key = issueKey(reportType, issueIndex)
    try {
      const payload = {
        status,
        note: resolutionNotes[key] || '',
        actor: 'human',
      }
      await (reportType === 'continuity'
        ? memoryApi.resolveContinuityIssue(projectId, drawer.id, issueIndex, payload)
        : memoryApi.resolveAuditIssue(projectId, drawer.id, issueIndex, payload))
      const refreshed = reportType === 'continuity'
        ? await memoryApi.getContinuityReport(projectId, drawer.id)
        : await memoryApi.getAuditReport(projectId, drawer.id)
      if (reportType === 'continuity') {
        setContinuityDrawer(refreshed)
      } else {
        setAuditDrawer(refreshed)
      }
      setResolutionNotes((prev) => ({ ...prev, [key]: '' }))
      await loadMemories()
      message.success('问题处理状态已保存')
    } catch (error) {
      message.error('保存问题处理状态失败')
    }
  }

  const currentDetail = memoryDetail[activeType]
  const currentJson = useMemo(() => {
    const list = currentDetail?.json || []
    if (!searchText) return list
    const needle = searchText.toLowerCase()
    return list.filter((item: any) => JSON.stringify(item, null, 2).toLowerCase().includes(needle))
  }, [currentDetail, searchText])

  const typeColumns = [
    { title: '类型', dataIndex: 'title', key: 'title' },
    {
      title: '状态',
      dataIndex: 'exists',
      key: 'exists',
      render: (exists: boolean) => <Tag color={exists ? 'success' : 'default'}>{exists ? '存在' : '缺失'}</Tag>,
    },
    { title: '条目', dataIndex: 'itemCount', key: 'itemCount', width: 90 },
    { title: '内容长度', dataIndex: 'contentLength', key: 'contentLength', width: 120 },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt' },
    { title: '路径', dataIndex: 'path', key: 'path' },
  ]

  const snapshotColumns = [
    { title: '章节', dataIndex: 'chapterTitle', key: 'chapterTitle' },
    { title: '书籍', dataIndex: 'bookId', key: 'bookId' },
    { title: '章节号', dataIndex: 'chapterNumber', key: 'chapterNumber', width: 90 },
    { title: '摄取时间', dataIndex: 'extractedAt', key: 'extractedAt' },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: any, record: any) => (
        <Button type="link" onClick={() => openSnapshot(record)}>
          查看
        </Button>
      ),
    },
  ]

  const continuityReportColumns = [
    { title: '章节', dataIndex: 'chapterTitle', key: 'chapterTitle' },
    { title: '章节号', dataIndex: 'chapterNumber', key: 'chapterNumber', width: 90 },
    {
      title: '结果',
      dataIndex: 'hasIssues',
      key: 'hasIssues',
      width: 100,
      render: (hasIssues: boolean) => (
        <Tag color={hasIssues ? 'error' : 'success'}>{hasIssues ? '有问题' : '通过'}</Tag>
      ),
    },
    { title: '严重', dataIndex: 'criticalCount', key: 'criticalCount', width: 80 },
    { title: '重要', dataIndex: 'majorCount', key: 'majorCount', width: 80 },
    { title: '轻微', dataIndex: 'minorCount', key: 'minorCount', width: 80 },
    {
      title: '待处理',
      dataIndex: 'openCount',
      key: 'openCount',
      width: 90,
      render: (openCount: number) => (
        <Tag color={openCount > 0 ? 'warning' : 'success'}>{openCount ?? 0}</Tag>
      ),
    },
    { title: '检查时间', dataIndex: 'checkedAt', key: 'checkedAt' },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: any, record: any) => (
        <Button type="link" onClick={() => openContinuityReport(record)}>
          查看
        </Button>
      ),
    },
  ]

  const auditReportColumns = [
    { title: '报告', dataIndex: 'id', key: 'id' },
    { title: '书籍', dataIndex: 'bookId', key: 'bookId', width: 120 },
    {
      title: '结果',
      dataIndex: 'hasIssues',
      key: 'hasIssues',
      width: 100,
      render: (hasIssues: boolean) => (
        <Tag color={hasIssues ? 'error' : 'success'}>{hasIssues ? '有冲突' : '通过'}</Tag>
      ),
    },
    { title: '问题', dataIndex: 'issueCount', key: 'issueCount', width: 80 },
    { title: '严重', dataIndex: 'criticalCount', key: 'criticalCount', width: 80 },
    { title: '重要', dataIndex: 'majorCount', key: 'majorCount', width: 80 },
    { title: '轻微', dataIndex: 'minorCount', key: 'minorCount', width: 80 },
    {
      title: '待处理',
      dataIndex: 'openCount',
      key: 'openCount',
      width: 90,
      render: (openCount: number) => (
        <Tag color={openCount > 0 ? 'warning' : 'success'}>{openCount ?? 0}</Tag>
      ),
    },
    { title: '审计时间', dataIndex: 'auditedAt', key: 'auditedAt' },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_: any, record: any) => (
        <Button type="link" onClick={() => openAuditReport(record)}>
          查看
        </Button>
      ),
    },
  ]

  const issueColumns = [
    { title: '类型', dataIndex: 'issue_type', key: 'issue_type', width: 110 },
    {
      title: '级别',
      dataIndex: 'severity',
      key: 'severity',
      width: 90,
      render: (severity: string) => (
        <Tag color={severity === 'critical' ? 'error' : severity === 'major' ? 'warning' : 'default'}>
          {severity}
        </Tag>
      ),
    },
    { title: '标题', dataIndex: 'title', key: 'title' },
    { title: '说明', dataIndex: 'description', key: 'description' },
    { title: '建议', dataIndex: 'suggestion', key: 'suggestion' },
  ]

  const buildIssueColumns = (reportType: 'continuity' | 'audit') => [
    ...issueColumns,
    {
      title: '状态',
      key: 'resolutionStatus',
      width: 110,
      render: (_: any, record: any) => {
        const status = resolutionStatus(record)
        return <Tag color={resolutionColor(status)}>{resolutionLabel(status)}</Tag>
      },
    },
    {
      title: '处理',
      key: 'resolutionAction',
      width: 320,
      render: (_: any, record: any, index: number) => {
        const key = issueKey(reportType, index)
        return (
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Input
              size="small"
              placeholder="处理备注"
              value={resolutionNotes[key] || ''}
              onChange={(event) =>
                setResolutionNotes((prev) => ({ ...prev, [key]: event.target.value }))
              }
            />
            <Space wrap>
              <Button size="small" onClick={() => handleResolveIssue(reportType, index, 'resolved')}>
                已解决
              </Button>
              <Button size="small" onClick={() => handleResolveIssue(reportType, index, 'accepted_risk')}>
                接受风险
              </Button>
              <Button size="small" onClick={() => handleResolveIssue(reportType, index, 'ignored')}>
                忽略
              </Button>
              <Button size="small" onClick={() => handleResolveIssue(reportType, index, 'open')}>
                重开
              </Button>
            </Space>
          </Space>
        )
      },
    },
  ]

  const resolutionColumns = [
    { title: '步骤', dataIndex: 'step', key: 'step', width: 80 },
    {
      title: '级别',
      dataIndex: 'severity',
      key: 'severity',
      width: 90,
      render: (severity: string) => (
        <Tag color={severity === 'critical' ? 'error' : severity === 'major' ? 'warning' : 'default'}>
          {severity}
        </Tag>
      ),
    },
    { title: '问题', dataIndex: 'title', key: 'title' },
    { title: '处理建议', dataIndex: 'action', key: 'action' },
  ]

  const latestContinuityTasks = (overview?.latestTasks || []).filter(
    (task: any) => task.taskType === 'continuity_check'
  )

  const tabItems = [
    {
      key: 'overview',
      label: '总览',
      children: (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Row gutter={16}>
            <Col span={6}>
              <Statistic title="记忆类型" value={overview?.types?.length || 0} />
            </Col>
            <Col span={6}>
              <Statistic
                title="总条目"
                value={(overview?.types || []).reduce((sum: number, type: any) => sum + (type.itemCount || 0), 0)}
              />
            </Col>
            <Col span={6}>
              <Statistic title="快照" value={snapshots.length} />
            </Col>
            <Col span={6}>
              <Statistic title="审计报告" value={auditReports.length} />
            </Col>
          </Row>
          <Table
            columns={typeColumns}
            dataSource={overview?.types || []}
            rowKey="type"
            loading={loading}
            pagination={false}
          />
          <Alert
            type="info"
            showIcon
            message="当前记忆以项目级 memory/*.md 与 JSON 文件同步保存；章节发布为终稿后会自动启动记忆摄取任务。"
          />
        </Space>
      ),
    },
    {
      key: 'memory',
      label: '记忆文件',
      children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            {memoryTypes.map((type) => (
              <Button
                key={type.key}
                type={activeType === type.key ? 'primary' : 'default'}
                onClick={() => setActiveType(type.key)}
              >
                {type.label}
              </Button>
            ))}
            <Search
              placeholder="搜索 JSON 条目"
              allowClear
              onSearch={setSearchText}
              onChange={(event) => setSearchText(event.target.value)}
              style={{ width: 260 }}
              prefix={<SearchOutlined />}
            />
          </Space>
          <Descriptions bordered column={2}>
            <Descriptions.Item label="文件">{currentDetail?.path || '-'}</Descriptions.Item>
            <Descriptions.Item label="更新时间">{currentDetail?.updatedAt || '-'}</Descriptions.Item>
            <Descriptions.Item label="JSON条目">{currentDetail?.itemCount ?? 0}</Descriptions.Item>
            <Descriptions.Item label="JSON路径">{currentDetail?.jsonPath || '-'}</Descriptions.Item>
          </Descriptions>
          <Row gutter={16}>
            <Col span={12}>
              <Card title="Markdown">
                {currentDetail?.content ? (
                  <Paragraph style={{ whiteSpace: 'pre-wrap', maxHeight: 560, overflow: 'auto' }}>
                    {currentDetail.content}
                  </Paragraph>
                ) : (
                  <Empty description="暂无Markdown记忆" />
                )}
              </Card>
            </Col>
            <Col span={12}>
              <Card title="JSON条目">
                {currentJson.length ? (
                  <List
                    dataSource={currentJson}
                    renderItem={(item: any) => (
                      <List.Item>
                        <pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
                          {JSON.stringify(item, null, 2)}
                        </pre>
                      </List.Item>
                    )}
                  />
                ) : (
                  <Empty description="暂无JSON条目" />
                )}
              </Card>
            </Col>
          </Row>
        </Space>
      ),
    },
    {
      key: 'snapshots',
      label: (
        <span>
          <ClockCircleOutlined />
          快照
        </span>
      ),
      children: (
        <Table
          columns={snapshotColumns}
          dataSource={snapshots}
          rowKey="id"
          loading={loading}
        />
      ),
    },
    {
      key: 'audit',
      label: '审计',
      children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="记忆审计会扫描人物、设定、剧情、伏笔和时间线 JSON，输出冲突、引用缺失和建议解决顺序。"
          />
          <Space>
            <Button type="primary" onClick={runMemoryAudit}>
              启动记忆审计
            </Button>
          </Space>
          <Table
            columns={auditReportColumns}
            dataSource={auditReports}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 8 }}
          />
        </Space>
      ),
    },
    {
      key: 'continuity',
      label: (
        <span>
          <CheckCircleOutlined />
          连续性
        </span>
      ),
      children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Form
            form={continuityForm}
            layout="inline"
            initialValues={{ bookId: 'default', volumeNumber: 1, chapterNumber: 1 }}
          >
            <Form.Item name="bookId" label="书籍">
              <Input style={{ width: 160 }} />
            </Form.Item>
            <Form.Item name="volumeNumber" label="卷">
              <InputNumber min={1} style={{ width: 90 }} />
            </Form.Item>
            <Form.Item name="chapterNumber" label="章" rules={[{ required: true, message: '请输入章节号' }]}>
              <InputNumber min={1} style={{ width: 90 }} />
            </Form.Item>
            <Form.Item name="chapterId" label="章节ID">
              <Input placeholder="默认 chapter_N" style={{ width: 180 }} />
            </Form.Item>
            <Form.Item>
              <Button type="primary" onClick={runContinuityCheck}>
                启动检查
              </Button>
            </Form.Item>
          </Form>
          <Table
            columns={continuityReportColumns}
            dataSource={continuityReports}
            rowKey="id"
            loading={loading}
            pagination={{ pageSize: 8 }}
          />
          <List
            header="最近连续性检查任务"
            dataSource={latestContinuityTasks}
            locale={{ emptyText: '暂无连续性检查任务' }}
            renderItem={(task: any) => (
              <List.Item>
                <List.Item.Meta
                  title={
                    <Space>
                      <Text>{task.id}</Text>
                      <Tag color={task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'error' : 'processing'}>
                        {task.status}
                      </Tag>
                      {task.result?.has_issues !== undefined && (
                        <Tag color={task.result.has_issues ? 'error' : 'success'}>
                          {task.result.has_issues ? '有问题' : '通过'}
                        </Tag>
                      )}
                    </Space>
                  }
                  description={task.createdAt}
                />
              </List.Item>
            )}
          />
        </Space>
      ),
    },
    {
      key: 'tasks',
      label: '任务',
      children: (
        <List
          dataSource={overview?.latestTasks || []}
          locale={{ emptyText: '暂无记忆任务' }}
          renderItem={(task: any) => (
            <List.Item>
              <List.Item.Meta
                title={
                  <Space>
                    <Text>{task.agentName}</Text>
                    <Tag color={task.status === 'SUCCESS' ? 'success' : task.status === 'FAILED' ? 'error' : 'processing'}>
                      {task.status}
                    </Tag>
                  </Space>
                }
                description={task.createdAt}
              />
            </List.Item>
          )}
        />
      ),
    },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <FileTextOutlined />
            记忆管理
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={loadMemories}>
              刷新
            </Button>
            <Button onClick={syncMemoryDb}>
              同步数据库
            </Button>
          </Space>
        }
      >
        <Tabs defaultActiveKey="overview" items={tabItems} />
      </Card>

      <Drawer
        title={snapshotDrawer?.chapter_title || snapshotDrawer?.chapterTitle || '记忆快照'}
        width={880}
        open={!!snapshotDrawer}
        onClose={() => setSnapshotDrawer(null)}
      >
        <pre style={{ whiteSpace: 'pre-wrap' }}>
          {JSON.stringify(snapshotDrawer, null, 2)}
        </pre>
      </Drawer>

      <Drawer
        title={continuityDrawer?.chapter_title || continuityDrawer?.chapterTitle || '连续性检查报告'}
        width={980}
        open={!!continuityDrawer}
        onClose={() => setContinuityDrawer(null)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions bordered column={3}>
            <Descriptions.Item label="章节">{continuityDrawer?.chapter_title || '-'}</Descriptions.Item>
            <Descriptions.Item label="严重">{continuityDrawer?.critical_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="重要">{continuityDrawer?.major_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="轻微">{continuityDrawer?.minor_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="待处理">{continuityDrawer?.resolutionSummary?.open_count ?? continuityDrawer?.resolution_summary?.open_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="已处理">{continuityDrawer?.resolutionSummary?.handled_count ?? continuityDrawer?.resolution_summary?.handled_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="报告路径" span={2}>{continuityDrawer?.path || '-'}</Descriptions.Item>
          </Descriptions>
          <Table
            columns={buildIssueColumns('continuity')}
            dataSource={continuityDrawer?.issues || []}
            rowKey={(_, index) => String(index)}
            pagination={{ pageSize: 6 }}
          />
          <pre style={{ whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(continuityDrawer, null, 2)}
          </pre>
        </Space>
      </Drawer>

      <Drawer
        title={auditDrawer?.id || '记忆审计报告'}
        width={1080}
        open={!!auditDrawer}
        onClose={() => setAuditDrawer(null)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions bordered column={3}>
            <Descriptions.Item label="书籍">{auditDrawer?.book_id || '-'}</Descriptions.Item>
            <Descriptions.Item label="问题数">{auditDrawer?.issue_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="严重">{auditDrawer?.critical_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="重要">{auditDrawer?.major_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="轻微">{auditDrawer?.minor_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="待处理">{auditDrawer?.resolutionSummary?.open_count ?? auditDrawer?.resolution_summary?.open_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="已处理">{auditDrawer?.resolutionSummary?.handled_count ?? auditDrawer?.resolution_summary?.handled_count ?? 0}</Descriptions.Item>
            <Descriptions.Item label="报告路径">{auditDrawer?.path || '-'}</Descriptions.Item>
          </Descriptions>
          <Card title="解决顺序" size="small">
            <Table
              columns={resolutionColumns}
              dataSource={auditDrawer?.resolution_plan || []}
              rowKey={(_, index) => String(index)}
              pagination={false}
            />
          </Card>
          <Card title="冲突详情" size="small">
            <Table
              columns={buildIssueColumns('audit')}
              dataSource={auditDrawer?.issues || []}
              rowKey={(_, index) => String(index)}
              pagination={{ pageSize: 8 }}
            />
          </Card>
          <pre style={{ whiteSpace: 'pre-wrap' }}>
            {JSON.stringify(auditDrawer, null, 2)}
          </pre>
        </Space>
      </Drawer>
    </Space>
  )
}

export default MemoryView
