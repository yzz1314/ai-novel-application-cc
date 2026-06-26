import React, { useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  List,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
} from 'antd'
import {
  BarChartOutlined,
  CheckCircleOutlined,
  ArrowRightOutlined,
  ClockCircleOutlined,
  EditOutlined,
  FileTextOutlined,
  PauseCircleOutlined,
  ProjectOutlined,
  ReloadOutlined,
  RocketOutlined,
  WarningOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { dashboardApi } from '../services/api'

const { Title, Paragraph, Text } = Typography

const statusColor = (status?: string) => {
  if (status === 'SUCCESS' || status === 'UP' || status === 'WRITING' || status === 'ANALYZED') return 'success'
  if (status === 'FAILED' || status === 'DOWN') return 'error'
  if (status === 'RUNNING' || status === 'OUTLINING' || status === 'INGESTING') return 'processing'
  if (status === 'PARTIAL') return 'blue'
  if (status === 'ARCHIVED' || status === 'CANCELLED') return 'default'
  return 'warning'
}

const healthColor = (status?: string) => {
  if (status === 'HEALTHY') return 'success'
  if (status === 'BUSY') return 'processing'
  if (status === 'WAITING_APPROVAL') return 'warning'
  if (status === 'ATTENTION' || status === 'DEGRADED') return 'error'
  return 'default'
}

const healthAlertType = (status?: string) => {
  if (status === 'HEALTHY' || status === 'BUSY') return 'success'
  if (status === 'WAITING_APPROVAL') return 'info'
  if (status === 'ATTENTION' || status === 'DEGRADED') return 'warning'
  return 'info'
}

const taskPercent = (task: any) => {
  if (task.status === 'SUCCESS') return 100
  const backendPercent = Number(task.progress?.percent)
  if (Number.isFinite(backendPercent) && backendPercent >= 0) {
    return Math.max(0, Math.min(100, Math.round(backendPercent)))
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') return 100
  if (task.status === 'RUNNING') return 45
  if (task.status === 'PENDING') return 5
  if (task.status === 'PARTIAL') return 70
  return 0
}

const taskProgressLabel = (task: any) => task.progress?.label || `${taskPercent(task)}%`

const actionPath = (target?: string) => {
  if (target === 'tasks') return '/tasks'
  if (target === 'projects') return '/projects'
  if (target === 'python-service') return '/tasks'
  return '/projects'
}

const Dashboard: React.FC = () => {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [dashboard, setDashboard] = useState<any>(null)

  useEffect(() => {
    loadDashboard()
  }, [])

  const stats = dashboard?.stats || {}
  const taskSummary = dashboard?.taskSummary || {}
  const recentProjects = dashboard?.recentProjects || []
  const recentTasks = dashboard?.recentTasks || []
  const workflowSummary = dashboard?.workflowSummary || []
  const serviceStatus = dashboard?.serviceStatus || {}
  const healthSummary = dashboard?.healthSummary || {}
  const blockedProjects = dashboard?.blockedProjects || []
  const nextActions = dashboard?.nextActions || []

  const activeTasks = Number(taskSummary.PENDING || 0) + Number(taskSummary.RUNNING || 0)
  const failedTasks = Number(taskSummary.FAILED || 0)
  const waitingApprovals = Number(healthSummary.waitingApprovals || 0)

  const serviceRows = useMemo(() => {
    return Object.entries(serviceStatus).map(([key, value]: [string, any]) => ({
      key,
      service: value?.service || key,
      status: value?.status || 'UNKNOWN',
    }))
  }, [serviceStatus])

  const loadDashboard = async () => {
    try {
      setLoading(true)
      const data = await dashboardApi.getOverview()
      setDashboard(data)
    } catch (error) {
      setDashboard(null)
    } finally {
      setLoading(false)
    }
  }

  const quickActions = [
    {
      title: '创建新项目',
      description: '开始一个新的小说创作项目',
      icon: <ProjectOutlined style={{ fontSize: 24, color: '#1890ff' }} />,
      action: () => navigate('/projects?action=create'),
    },
    {
      title: '任务中心',
      description: '查看运行状态、失败诊断和 checkpoint 恢复',
      icon: <ClockCircleOutlined style={{ fontSize: 24, color: '#52c41a' }} />,
      action: () => navigate('/tasks'),
    },
    {
      title: '模型配置',
      description: '管理主模型、快速模型和连通性测试',
      icon: <RocketOutlined style={{ fontSize: 24, color: '#faad14' }} />,
      action: () => navigate('/models'),
    },
  ]

  const workflowColumns = [
    {
      title: '项目',
      dataIndex: 'project',
      key: 'project',
      render: (project: any) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/projects/${project.id}`)}>
          {project.name || project.id}
        </Button>
      ),
    },
    {
      title: '进度',
      dataIndex: 'progress',
      key: 'progress',
      width: 180,
      render: (progress: number) => <Progress percent={progress || 0} size="small" />,
    },
    {
      title: '样本/分析',
      key: 'samples',
      width: 130,
      render: (_: any, record: any) => `${record.analyzedSamples || 0}/${record.samples || 0}`,
    },
    {
      title: 'Skill/大纲',
      key: 'plan',
      width: 130,
      render: (_: any, record: any) => `${record.skills || 0}/${record.outlines || 0}`,
    },
    {
      title: '章节',
      key: 'chapters',
      width: 130,
      render: (_: any, record: any) => `${record.draftChapters || 0} 草稿 / ${record.finalChapters || 0} 终稿`,
    },
    {
      title: '记忆/图谱/检索',
      key: 'knowledge',
      width: 170,
      render: (_: any, record: any) => (
        <Space wrap>
          <Tag color={record.memoryArtifacts ? 'green' : 'default'}>M {record.memoryArtifacts || 0}</Tag>
          <Tag color={record.graphArtifacts ? 'green' : 'default'}>G {record.graphArtifacts || 0}</Tag>
          <Tag color={record.retrievalArtifacts ? 'green' : 'default'}>R {record.retrievalArtifacts || 0}</Tag>
        </Space>
      ),
    },
    {
      title: '失败任务',
      dataIndex: 'failedTasks',
      key: 'failedTasks',
      width: 100,
      render: (value: number) => <Tag color={value ? 'error' : 'success'}>{value || 0}</Tag>,
    },
  ]

  const blockedProjectColumns = [
    {
      title: '项目',
      dataIndex: 'project',
      key: 'project',
      render: (project: any) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/projects/${project.id}`)}>
          {project.name || project.id}
        </Button>
      ),
    },
    {
      title: '阻塞项',
      dataIndex: 'blocker',
      key: 'blocker',
      width: 150,
      render: (blocker: string) => {
        const label = blocker === 'FAILED_TASK'
          ? '失败任务'
          : blocker === 'WAITING_APPROVAL' ? '等待审批' : '部分完成'
        return <Tag color={statusColor(blocker === 'FAILED_TASK' ? 'FAILED' : 'PARTIAL')}>{label}</Tag>
      },
    },
    {
      title: '任务计数',
      key: 'counts',
      width: 190,
      render: (_: any, record: any) => (
        <Space wrap>
          <Tag color={record.failedTasks ? 'error' : 'default'}>失败 {record.failedTasks || 0}</Tag>
          <Tag color={record.partialTasks ? 'blue' : 'default'}>部分 {record.partialTasks || 0}</Tag>
          <Tag color={record.waitingApprovals ? 'warning' : 'default'}>审批 {record.waitingApprovals || 0}</Tag>
        </Space>
      ),
    },
    {
      title: '最近任务',
      key: 'latestTask',
      render: (_: any, record: any) => record.latestTask ? (
        <Space direction="vertical" size={0}>
          <Text>{record.latestTask.taskType || record.latestTask.agentName}</Text>
          <Text type="secondary">{record.latestTask.id}</Text>
        </Space>
      ) : '-',
    },
  ]

  const taskColumns = [
    {
      title: '任务',
      dataIndex: 'id',
      key: 'id',
      render: (value: string, record: any) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{record.taskType} / {record.agentName}</Text>
        </Space>
      ),
    },
    {
      title: '项目',
      dataIndex: 'projectId',
      key: 'projectId',
      width: 190,
      render: (projectId: string) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => navigate(`/projects/${projectId}`)}>
          {projectId}
        </Button>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 170,
      render: (status: string, record: any) => (
        <Space direction="vertical" size={2} style={{ width: 140 }}>
          <Tag color={statusColor(status)}>{status}</Tag>
          <Progress percent={taskPercent(record)} size="small" />
          <Text type="secondary" style={{ fontSize: 12 }}>{taskProgressLabel(record)}</Text>
        </Space>
      ),
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  ]

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={2} style={{ marginBottom: 4 }}>工作台</Title>
          <Paragraph type="secondary" style={{ marginBottom: 0 }}>
            从项目、任务、章节产物和服务健康状态看创作生产线。
          </Paragraph>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadDashboard} loading={loading}>
          刷新
        </Button>
      </Space>

      {failedTasks > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={`当前共有 ${failedTasks} 个失败任务，可进入任务中心查看诊断并重试。`}
          action={<Button size="small" onClick={() => navigate('/tasks')}>任务中心</Button>}
        />
      ) : null}

      {dashboard ? (
        <Alert
          type={healthAlertType(healthSummary.status)}
          showIcon
          message={
            <Space wrap>
              <Text strong>生产线状态</Text>
              <Tag color={healthColor(healthSummary.status)}>{healthSummary.status || 'UNKNOWN'}</Tag>
              <Text>{healthSummary.message || '暂无状态摘要'}</Text>
            </Space>
          }
        />
      ) : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="项目总数" value={stats.totalProjects || 0} prefix={<ProjectOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="样本总数" value={stats.totalSamples || 0} prefix={<FileTextOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="章节产物" value={stats.totalChapters || 0} prefix={<EditOutlined />} />
          </Card>
        </Col>
        <Col xs={24} sm={12} lg={6}>
          <Card loading={loading}>
            <Statistic title="运行/等待任务" value={activeTasks} prefix={<RocketOutlined />} />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} md={8}>
          <Card loading={loading}>
            <Statistic
              title="任务成功率"
              value={healthSummary.successRate || 0}
              suffix="%"
              prefix={<CheckCircleOutlined />}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card loading={loading}>
            <Statistic
              title="等待审批"
              value={waitingApprovals}
              prefix={<PauseCircleOutlined />}
              valueStyle={{ color: waitingApprovals ? '#faad14' : undefined }}
            />
          </Card>
        </Col>
        <Col xs={24} md={8}>
          <Card loading={loading}>
            <Statistic
              title="失败任务"
              value={failedTasks}
              prefix={<WarningOutlined />}
              valueStyle={{ color: failedTasks ? '#cf1322' : undefined }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={16}>
          <Card title="快速操作" style={{ marginBottom: 16 }}>
            <Row gutter={[16, 16]}>
              {quickActions.map((action) => (
                <Col xs={24} md={8} key={action.title}>
                  <Card hoverable style={{ textAlign: 'center', height: 170 }} onClick={action.action}>
                    <div style={{ marginBottom: 16 }}>{action.icon}</div>
                    <Title level={5}>{action.title}</Title>
                    <Paragraph type="secondary" style={{ fontSize: 12 }}>
                      {action.description}
                    </Paragraph>
                  </Card>
                </Col>
              ))}
            </Row>
          </Card>

          <Card title="下一步动作" style={{ marginBottom: 16 }}>
            {nextActions.length ? (
              <List
                dataSource={nextActions}
                renderItem={(action: any) => (
                  <List.Item
                    actions={[
                      <Button type="link" onClick={() => navigate(actionPath(action.target))}>
                        进入
                      </Button>,
                    ]}
                  >
                    <List.Item.Meta
                      avatar={<BarChartOutlined style={{ color: '#1677ff' }} />}
                      title={action.title}
                      description={action.description}
                    />
                  </List.Item>
                )}
              />
            ) : (
              <Empty description="暂无建议动作" />
            )}
          </Card>

          <Card title="项目生产线">
            <Table
              columns={workflowColumns}
              dataSource={workflowSummary}
              rowKey={(record: any) => record.project?.id}
              loading={loading}
              pagination={false}
              scroll={{ x: 1000 }}
              locale={{ emptyText: <Empty description="暂无项目生产线数据" /> }}
            />
          </Card>
        </Col>

        <Col xs={24} lg={8}>
          <Card
            title="最近项目"
            extra={<Button type="link" onClick={() => navigate('/projects')}>查看全部</Button>}
            style={{ marginBottom: 16 }}
            loading={loading}
          >
            {recentProjects.length ? (
              <List
                dataSource={recentProjects}
                renderItem={(item: any) => (
                  <List.Item>
                    <List.Item.Meta
                      title={
                        <a onClick={() => navigate(`/projects/${item.project.id}`)}>
                          {item.project.name}
                        </a>
                      }
                      description={`样本 ${item.sampleCount || 0} / 章节 ${item.chapterCount || 0} / 任务 ${item.taskCount || 0}`}
                    />
                    <Tag color={statusColor(item.project.status)}>{item.project.status}</Tag>
                  </List.Item>
                )}
              />
            ) : (
              <Empty description="暂无项目" />
            )}
          </Card>

          <Card title="服务状态" loading={loading} style={{ marginBottom: 16 }}>
            {serviceRows.length ? (
              <List
                dataSource={serviceRows}
                renderItem={(item) => (
                  <List.Item>
                    <Text>{item.service}</Text>
                    <Tag color={statusColor(item.status)}>{item.status}</Tag>
                  </List.Item>
                )}
              />
            ) : (
              <Empty description="暂无服务状态" />
            )}
          </Card>

          <Card title="任务分布" loading={loading}>
            <Space wrap>
              {['PENDING', 'RUNNING', 'PARTIAL', 'SUCCESS', 'FAILED', 'CANCELLED'].map((status) => (
                <Tag key={status} color={statusColor(status)}>
                  {status} {taskSummary[status] || 0}
                </Tag>
              ))}
            </Space>
          </Card>
        </Col>
      </Row>

      <Card title="阻塞项目">
        <Table
          columns={blockedProjectColumns}
          dataSource={blockedProjects}
          rowKey={(record: any) => record.project?.id}
          loading={loading}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无阻塞项目" /> }}
        />
      </Card>

      <Card
        title="最近任务"
        extra={<Button type="link" onClick={() => navigate('/tasks')}>查看全部</Button>}
      >
        <Table
          columns={taskColumns}
          dataSource={recentTasks}
          rowKey="id"
          loading={loading}
          pagination={false}
          locale={{ emptyText: <Empty description="暂无任务" /> }}
        />
      </Card>

      <Card title="创作流程">
        <List
          dataSource={[
            { title: '1. 创建项目', description: '设置项目基本信息' },
            { title: '2. 上传样本', description: '上传同类型样本小说并完成分块' },
            { title: '3. 分析生成 Skill', description: '自动分析样本并沉淀创作指导' },
            { title: '4. 规划大纲', description: '生成 Project Soul、总纲、卷纲、章纲和边界控制' },
            { title: '5. 创作正文', description: '基于检索上下文、记忆和图谱生成章节正文' },
          ]}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta title={item.title} description={item.description} />
              <ArrowRightOutlined style={{ color: '#d9d9d9' }} />
            </List.Item>
          )}
        />
      </Card>
    </Space>
  )
}

export default Dashboard
