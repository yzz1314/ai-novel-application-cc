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
  Modal,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Typography,
  message,
} from 'antd'
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  FileSearchOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  RedoOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { taskApi } from '../services/api'

const { Paragraph, Text, Title } = Typography

const statusOptions = ['PENDING', 'RUNNING', 'PARTIAL', 'SUCCESS', 'FAILED', 'CANCELLED']

const statusColor = (status?: string) => {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'RUNNING') return 'processing'
  if (status === 'PENDING') return 'warning'
  if (status === 'PARTIAL') return 'blue'
  if (status === 'CANCELLED') return 'default'
  return 'default'
}

const eventColor = (eventType?: string) => {
  if (eventType === 'created' || eventType === 'started') return 'processing'
  if (eventType === 'finished') return 'success'
  if (eventType === 'failed') return 'error'
  if (eventType === 'cancelled' || eventType === 'cancel_preserved') return 'default'
  if (eventType?.includes('retry') || eventType?.includes('resume')) return 'blue'
  return 'default'
}

const taskPercent = (task: any) => {
  if (task.status === 'SUCCESS') return 100
  const backendPercent = Number(task.progress?.percent)
  if (Number.isFinite(backendPercent) && backendPercent >= 0) {
    return Math.max(0, Math.min(100, Math.round(backendPercent)))
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') return 100
  const metricsPercent = Number(task.metrics?.progress ?? task.result?.progress ?? task.result?.progress_percent)
  if (Number.isFinite(metricsPercent) && metricsPercent >= 0) {
    return Math.max(0, Math.min(100, metricsPercent <= 1 ? Math.round(metricsPercent * 100) : Math.round(metricsPercent)))
  }
  if (task.status === 'RUNNING') return 45
  if (task.status === 'PENDING') return 5
  if (task.status === 'PARTIAL') return 70
  return 0
}

const taskProgressLabel = (task: any) => task.progress?.label || `${taskPercent(task)}%`

const eventDetailsText = (details: any) => {
  if (!details || Object.keys(details).length === 0) return '-'
  return JSON.stringify(details)
}

const isWaitingForHuman = (task: any) =>
  task?.status === 'PARTIAL' && !!task?.result?.waiting_for_human?.node_id

const taskIdentity = (task: any) => task?.id || task?.taskId

const TaskCenter: React.FC = () => {
  const navigate = useNavigate()
  const [form] = Form.useForm()
  const [approvalForm] = Form.useForm()
  const [loading, setLoading] = useState(false)
  const [tasks, setTasks] = useState<any[]>([])
  const [taskDrawer, setTaskDrawer] = useState<any>(null)
  const [resumeInput, setResumeInput] = useState('{}')
  const [approvalTask, setApprovalTask] = useState<any>(null)
  const [approvalDecision, setApprovalDecision] = useState<'approve' | 'reject'>('approve')
  const [approvalSubmitting, setApprovalSubmitting] = useState(false)

  useEffect(() => {
    loadTasks()
  }, [])

  const stats = useMemo(() => {
    const counts = statusOptions.reduce((acc: any, status) => {
      acc[status] = tasks.filter((task) => task.status === status).length
      return acc
    }, {})
    return {
      total: tasks.length,
      active: counts.PENDING + counts.RUNNING,
      failed: counts.FAILED,
      waiting: tasks.filter((task) => task.checkpointRef && ['PARTIAL', 'FAILED', 'CANCELLED'].includes(task.status)).length,
      approvals: tasks.filter((task) => isWaitingForHuman(task)).length,
      counts,
    }
  }, [tasks])

  const loadTasks = async () => {
    try {
      setLoading(true)
      const values = form.getFieldsValue()
      const data = await taskApi.getList(undefined, {
        projectId: values.projectId || undefined,
        status: values.status || undefined,
        limit: values.limit || 100,
      })
      setTasks(data || [])
    } catch (error) {
      setTasks([])
    } finally {
      setLoading(false)
    }
  }

  const openTaskLogs = async (taskId: string) => {
    try {
      const data = await taskApi.getLogs(taskId)
      setTaskDrawer(data)
      const waiting = data?.result?.waiting_for_human
      const resumePayload = waiting?.node_id
        ? {
            human_confirmations: {
              [waiting.node_id]: {
                approved: true,
                reviewer: 'human',
                note: '',
              },
            },
          }
        : data?.parameters?.resume_input || { decision: 'approve' }
      setResumeInput(JSON.stringify(resumePayload, null, 2))
    } catch (error) {
      message.error('加载任务详情失败')
    }
  }

  const retryTask = async (taskId: string) => {
    try {
      await taskApi.retry(taskId)
      message.success('任务已重新提交')
      await loadTasks()
    } catch (error) {
      message.error('任务重试失败')
    }
  }

  const cancelTask = async (taskId: string) => {
    try {
      await taskApi.cancel(taskId)
      message.success('任务已取消')
      await loadTasks()
    } catch (error) {
      message.error('任务取消失败')
    }
  }

  const resumeTask = async (taskId: string) => {
    try {
      let payload: any = {}
      if (resumeInput.trim()) {
        payload = JSON.parse(resumeInput)
      }
      await taskApi.resume(taskId, payload)
      message.success('恢复任务已启动')
      setTaskDrawer(null)
      await loadTasks()
    } catch (error) {
      message.error('恢复任务失败，请检查 JSON 输入')
    }
  }

  const openApprovalModal = (task: any, decision: 'approve' | 'reject') => {
    if (!isWaitingForHuman(task)) {
      message.warning('这个任务没有等待人工确认的节点')
      return
    }
    setApprovalTask(task)
    setApprovalDecision(decision)
    approvalForm.setFieldsValue({
      reviewer: 'human',
      note: '',
    })
  }

  const submitApproval = async () => {
    if (!approvalTask) return
    const waiting = approvalTask.result?.waiting_for_human
    const taskId = taskIdentity(approvalTask)
    if (!waiting?.node_id || !taskId) {
      message.warning('这个任务没有可恢复的人工确认节点')
      return
    }

    try {
      const values = await approvalForm.validateFields()
      setApprovalSubmitting(true)
      await taskApi.resume(taskId, {
        human_confirmations: {
          [waiting.node_id]: {
            approved: approvalDecision === 'approve',
            reviewer: values.reviewer || 'human',
            note: values.note || '',
            decided_at: new Date().toISOString(),
          },
        },
      })
      message.success(approvalDecision === 'approve' ? '已批准，工作流恢复执行' : '已驳回，工作流将取消')
      setApprovalTask(null)
      setTaskDrawer(null)
      await loadTasks()
    } catch (error) {
      message.error('提交工作流审批失败')
    } finally {
      setApprovalSubmitting(false)
    }
  }

  const columns = [
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
        <Button type="link" onClick={() => navigate(`/projects/${projectId}`)} style={{ padding: 0 }}>
          {projectId}
        </Button>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (status: string, record: any) => (
        <Space direction="vertical" size={2} style={{ width: 110 }}>
          <Tag color={statusColor(status)}>{status}</Tag>
          {isWaitingForHuman(record) && <Tag color="warning">等待人工确认</Tag>}
          <Progress percent={taskPercent(record)} size="small" />
          <Text type="secondary" style={{ fontSize: 12 }}>{taskProgressLabel(record)}</Text>
        </Space>
      ),
    },
    {
      title: '模型/耗时',
      key: 'metrics',
      width: 150,
      render: (_: any, record: any) => (
        <Space direction="vertical" size={0}>
          <Text>{record.metrics?.model || record.metrics?.model_profile_id || '-'}</Text>
          <Text type="secondary">{record.metrics?.duration_ms ? `${record.metrics.duration_ms} ms` : '-'}</Text>
        </Space>
      ),
    },
    {
      title: '重试',
      dataIndex: 'retryCount',
      key: 'retryCount',
      width: 80,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
    },
    {
      title: '完成时间',
      dataIndex: 'finishedAt',
      key: 'finishedAt',
      width: 180,
      render: (value: string) => value || '-',
    },
    {
      title: '操作',
      key: 'action',
      fixed: 'right' as const,
      width: 300,
      render: (_: any, record: any) => (
        <Space wrap>
          <Button size="small" icon={<FileSearchOutlined />} onClick={() => openTaskLogs(record.id)}>
            详情
          </Button>
          {isWaitingForHuman(record) && (
            <>
              <Button size="small" type="primary" icon={<CheckCircleOutlined />} onClick={() => openApprovalModal(record, 'approve')}>
                批准
              </Button>
              <Button size="small" danger icon={<CloseCircleOutlined />} onClick={() => openApprovalModal(record, 'reject')}>
                驳回
              </Button>
            </>
          )}
          {['FAILED', 'CANCELLED', 'PARTIAL'].includes(record.status) && !isWaitingForHuman(record) && (
            <Button size="small" icon={<RedoOutlined />} onClick={() => retryTask(record.id)}>
              重试
            </Button>
          )}
          {['PENDING', 'RUNNING'].includes(record.status) && (
            <Button size="small" danger icon={<StopOutlined />} onClick={() => cancelTask(record.id)}>
              取消
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const eventColumns = [
    {
      title: '时间',
      dataIndex: 'timestamp',
      key: 'timestamp',
      width: 180,
      render: (value: string) => value || '-',
    },
    {
      title: '事件',
      dataIndex: 'eventType',
      key: 'eventType',
      width: 170,
      render: (value: string) => <Tag color={eventColor(value)}>{value || '-'}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (value: string) => <Tag color={statusColor(value)}>{value || '-'}</Tag>,
    },
    {
      title: '详情',
      dataIndex: 'details',
      key: 'details',
      render: (value: any) => (
        <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
          {eventDetailsText(value)}
        </Paragraph>
      ),
    },
  ]

  const drawerEvents = Array.isArray(taskDrawer?.events) ? taskDrawer.events : []

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <div>
          <Title level={3} style={{ marginBottom: 4 }}>任务中心</Title>
          <Text type="secondary">集中查看所有项目任务、运行状态、诊断信息和 checkpoint 恢复入口。</Text>
        </div>
        <Button icon={<ReloadOutlined />} onClick={loadTasks} loading={loading}>
          刷新
        </Button>
      </Space>

      <Row gutter={16}>
        <Col span={6}>
          <Card><Statistic title="任务总数" value={stats.total} prefix={<ClockCircleOutlined />} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="运行/等待" value={stats.active} prefix={<PlayCircleOutlined />} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="失败任务" value={stats.failed} valueStyle={{ color: stats.failed ? '#cf1322' : undefined }} /></Card>
        </Col>
        <Col span={6}>
          <Card><Statistic title="等待人工确认" value={stats.approvals} prefix={<PauseCircleOutlined />} /></Card>
        </Col>
      </Row>

      {stats.approvals > 0 ? (
        <Alert
          type="info"
          showIcon
          message={`当前筛选范围内有 ${stats.approvals} 个工作流等待人工确认，可在操作列直接批准或驳回。`}
        />
      ) : null}

      {stats.failed > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={`当前筛选范围内有 ${stats.failed} 个失败任务，可在操作列查看诊断或重试。`}
        />
      ) : null}

      <Card title="筛选">
        <Form form={form} layout="inline" initialValues={{ limit: 100 }}>
          <Form.Item name="projectId" label="项目 ID">
            <Input allowClear placeholder="留空查看全部项目" style={{ width: 260 }} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 150 }}
              options={statusOptions.map((status) => ({ label: status, value: status }))}
            />
          </Form.Item>
          <Form.Item name="limit" label="数量">
            <InputNumber min={1} max={200} style={{ width: 100 }} />
          </Form.Item>
          <Form.Item>
            <Button type="primary" onClick={loadTasks} loading={loading}>
              查询
            </Button>
          </Form.Item>
        </Form>
      </Card>

      <Card title="任务列表">
        <Table
          columns={columns}
          dataSource={tasks}
          rowKey="id"
          loading={loading}
          scroll={{ x: 1300 }}
          locale={{ emptyText: <Empty description="暂无任务" /> }}
        />
      </Card>

      <Drawer
        title={taskDrawer?.taskId || '任务详情'}
        width={920}
        open={!!taskDrawer}
        onClose={() => setTaskDrawer(null)}
      >
        {taskDrawer ? (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label="项目">{taskDrawer.projectId}</Descriptions.Item>
              <Descriptions.Item label="状态">
                <Tag color={statusColor(taskDrawer.status)}>{taskDrawer.status}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="任务类型">{taskDrawer.taskType}</Descriptions.Item>
              <Descriptions.Item label="Agent">{taskDrawer.agentName}</Descriptions.Item>
              <Descriptions.Item label="Checkpoint" span={2}>{taskDrawer.checkpointRef || '-'}</Descriptions.Item>
              <Descriptions.Item label="Progress" span={2}>
                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                  <Progress percent={taskPercent(taskDrawer)} size="small" />
                  <Text type="secondary">{taskProgressLabel(taskDrawer)}</Text>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">{taskDrawer.createdAt}</Descriptions.Item>
              <Descriptions.Item label="完成时间">{taskDrawer.finishedAt || '-'}</Descriptions.Item>
            </Descriptions>

            {isWaitingForHuman(taskDrawer) ? (
              <Alert
                type="info"
                showIcon
                message={taskDrawer.result?.waiting_for_human?.prompt || '等待人工确认'}
                description={
                  <Space direction="vertical" size={2}>
                    <Text>节点：{taskDrawer.result?.waiting_for_human?.node_id}</Text>
                    {taskDrawer.checkpointRef && <Text>Checkpoint：{taskDrawer.checkpointRef}</Text>}
                    <Space wrap style={{ marginTop: 8 }}>
                      <Button type="primary" icon={<CheckCircleOutlined />} onClick={() => openApprovalModal(taskDrawer, 'approve')}>
                        批准并恢复
                      </Button>
                      <Button danger icon={<CloseCircleOutlined />} onClick={() => openApprovalModal(taskDrawer, 'reject')}>
                        驳回并取消
                      </Button>
                    </Space>
                  </Space>
                }
              />
            ) : null}

            {taskDrawer.checkpointRef && !['RUNNING', 'PENDING'].includes(taskDrawer.status) ? (
              <Card title="Checkpoint 恢复" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Input.TextArea
                    value={resumeInput}
                    onChange={(event) => setResumeInput(event.target.value)}
                    rows={5}
                  />
                  <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => resumeTask(taskDrawer.taskId)}>
                    恢复执行
                  </Button>
                </Space>
              </Card>
            ) : null}

            <Card
              title="事件日志"
              size="small"
              extra={<Text type="secondary">{taskDrawer.eventLogPath || '-'}</Text>}
            >
              <Table
                columns={eventColumns}
                dataSource={drawerEvents}
                rowKey={(_, index) => `${taskDrawer.taskId}-event-${index}`}
                size="small"
                pagination={false}
                scroll={{ x: 760 }}
                locale={{ emptyText: <Empty description="暂无事件日志" /> }}
              />
            </Card>

            <Card title="诊断 JSON" size="small">
              <Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
                {JSON.stringify(taskDrawer, null, 2)}
              </Paragraph>
            </Card>
          </Space>
        ) : null}
      </Drawer>

      <Modal
        title={approvalDecision === 'approve' ? '批准工作流继续执行' : '驳回并取消工作流'}
        open={!!approvalTask}
        confirmLoading={approvalSubmitting}
        okText={approvalDecision === 'approve' ? '批准并恢复' : '确认驳回'}
        okButtonProps={{ danger: approvalDecision === 'reject' }}
        cancelText="取消"
        onOk={submitApproval}
        onCancel={() => setApprovalTask(null)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type={approvalDecision === 'approve' ? 'info' : 'warning'}
            showIcon
            message={approvalTask?.result?.waiting_for_human?.prompt || '等待人工确认'}
            description={
              <Space direction="vertical" size={0}>
                <Text>任务：{taskIdentity(approvalTask)}</Text>
                <Text>节点：{approvalTask?.result?.waiting_for_human?.node_id}</Text>
                {approvalTask?.checkpointRef && <Text>Checkpoint：{approvalTask.checkpointRef}</Text>}
              </Space>
            }
          />
          <Form form={approvalForm} layout="vertical">
            <Form.Item
              name="reviewer"
              label="审批人"
              rules={[{ required: true, message: '请输入审批人' }]}
            >
              <Input placeholder="human" />
            </Form.Item>
            <Form.Item name="note" label="审批意见">
              <Input.TextArea rows={4} placeholder="记录本次批准或驳回原因" />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </Space>
  )
}

export default TaskCenter
