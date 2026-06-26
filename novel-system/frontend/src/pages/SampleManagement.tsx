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
  List,
  Modal,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  Upload,
  message,
} from 'antd'
import {
  DeleteOutlined,
  EyeOutlined,
  FileDoneOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  InboxOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
} from '@ant-design/icons'
import { useParams } from 'react-router-dom'
import { analysisApi, sampleApi, taskApi } from '../services/api'
import type { UploadProps } from 'antd'

const { Dragger } = Upload
const { Paragraph, Text, Title } = Typography

interface Sample {
  id: string
  sampleId?: string
  title?: string
  sampleName: string
  fileName: string
  author?: string
  genre?: string
  status: string
  uploadTime: string
  createdAt?: string
  totalChars?: number
  totalChapters?: number
  chunkCount?: number
  analysisCount?: number
  hasBookReport?: boolean
}

const statusMap: Record<string, { color: string; text: string }> = {
  UPLOADED: { color: 'default', text: '已上传' },
  NORMALIZED: { color: 'processing', text: '已规范化' },
  CHUNKED: { color: 'blue', text: '已分块' },
  ANALYZED: { color: 'success', text: '已分析' },
  PENDING: { color: 'default', text: '等待中' },
  RUNNING: { color: 'processing', text: '执行中' },
  PARTIAL: { color: 'warning', text: '部分完成' },
  SUCCESS: { color: 'success', text: '成功' },
  FAILED: { color: 'error', text: '失败' },
  CANCELLED: { color: 'default', text: '已取消' },
}

const SampleManagement: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>()
  const [samples, setSamples] = useState<Sample[]>([])
  const [tasks, setTasks] = useState<any[]>([])
  const [analysisStatus, setAnalysisStatus] = useState<any>(null)
  const [loading, setLoading] = useState(false)
  const [uploadModalVisible, setUploadModalVisible] = useState(false)
  const [detailLoading, setDetailLoading] = useState(false)
  const [selectedSample, setSelectedSample] = useState<Sample | null>(null)
  const [sampleArtifacts, setSampleArtifacts] = useState<any>(null)
  const [chunks, setChunks] = useState<any[]>([])
  const [bookReport, setBookReport] = useState<any>(null)
  const [coverageReport, setCoverageReport] = useState<any>(null)
  const [reportDrawer, setReportDrawer] = useState<any>(null)
  const [taskDrawer, setTaskDrawer] = useState<any>(null)
  const [form] = Form.useForm()
  const [fileList, setFileList] = useState<any[]>([])

  useEffect(() => {
    loadData()
  }, [projectId])

  const runningTasks = useMemo(
    () => tasks.some((task) => ['PENDING', 'RUNNING'].includes(task.status)),
    [tasks]
  )

  useEffect(() => {
    if (!projectId || !runningTasks) return
    const timer = window.setInterval(() => loadData(true), 3000)
    return () => window.clearInterval(timer)
  }, [projectId, runningTasks])

  const loadData = async (silent = false) => {
    if (!projectId) return
    try {
      if (!silent) setLoading(true)
      const [sampleData, taskData, statusData] = await Promise.all([
        sampleApi.getList(projectId),
        taskApi.getList(projectId).catch(() => []),
        analysisApi.getStatus(projectId).catch(() => null),
      ])
      const artifactSamples = new Map<string, any>(
        (statusData?.samples || []).map((sample: any) => [sample.sampleId, sample])
      )
      setSamples((sampleData || []).map((sample: any) => normalizeSample(sample, artifactSamples)))
      setTasks(taskData || [])
      setAnalysisStatus(statusData)
    } catch (error) {
      if (!silent) message.error('加载样本列表失败')
    } finally {
      if (!silent) setLoading(false)
    }
  }

  const normalizeSample = (sample: any, artifactSamples?: Map<string, any>): Sample => {
    const sampleId = sample.sampleId || sample.id
    const artifact = artifactSamples?.get(sampleId) || {}
    return {
      ...sample,
      ...artifact,
      id: sample.id || sampleId,
      sampleId,
      sampleName: sample.title || sample.sampleName || sample.fileName || sample.id,
      fileName: sample.fileName || '-',
      status: sample.status || artifact.status || 'UNKNOWN',
      uploadTime: sample.createdAt || sample.created_at || sample.uploadTime || '',
      totalChars: sample.totalChars ?? artifact.totalChars,
      totalChapters: sample.totalChapters ?? artifact.totalChapters,
      chunkCount: artifact.chunkCount,
      analysisCount: artifact.analysisCount,
      hasBookReport: artifact.hasBookReport,
    }
  }

  const activeTaskForSample = (sampleId: string) =>
    tasks.find((task) =>
      ['PENDING', 'RUNNING'].includes(task.status)
      && (task.inputRefs?.sample_id === sampleId || task.inputRefs?.sampleId === sampleId)
    )

  const uploadProps: UploadProps = {
    name: 'file',
    multiple: false,
    accept: '.txt,.md',
    fileList,
    beforeUpload: (file) => {
      const isSupported = file.name.endsWith('.txt') || file.name.endsWith('.md')
      if (!isSupported) {
        message.error('只能上传 TXT 或 Markdown 文件')
        return false
      }
      const isLt20M = file.size / 1024 / 1024 < 20
      if (!isLt20M) {
        message.error('文件大小不能超过20MB')
        return false
      }
      setFileList([file])
      return false
    },
    onRemove: () => {
      setFileList([])
    },
  }

  const handleUpload = async (values: any) => {
    if (fileList.length === 0) {
      message.error('请选择文件')
      return
    }

    if (!projectId) return

    try {
      setLoading(true)
      const file = fileList[0]

      await sampleApi.upload(projectId, file, {
        sampleName: values.sampleName,
        author: values.author,
        genre: values.genre,
      })

      message.success('样本上传成功，导入分块任务已启动')

      setUploadModalVisible(false)
      form.resetFields()
      setFileList([])
      await loadData(true)
    } catch (error) {
      message.error('上传失败')
    } finally {
      setLoading(false)
    }
  }

  const startAnalysis = async (sampleId: string) => {
    if (!projectId) return

    try {
      setLoading(true)
      await analysisApi.analyzeFullText(projectId, sampleId)
      message.success('全文分析任务已启动')
      await loadData(true)
    } catch (error) {
      message.error('启动分析失败')
    } finally {
      setLoading(false)
    }
  }

  const summarizeBook = async (sampleId: string) => {
    if (!projectId) return

    try {
      setLoading(true)
      await analysisApi.summarizeBook(projectId, sampleId)
      message.success('单书汇总任务已启动')
      await loadData(true)
    } catch (error) {
      message.error('启动单书汇总失败')
    } finally {
      setLoading(false)
    }
  }

  const checkCoverage = async (sampleId: string) => {
    if (!projectId) return

    try {
      setLoading(true)
      await analysisApi.checkCoverage(projectId, sampleId)
      message.success('覆盖率校验任务已启动')
      await loadData(true)
    } catch (error) {
      message.error('启动覆盖率校验失败')
    } finally {
      setLoading(false)
    }
  }

  const repairAnalysis = async (sampleId: string) => {
    if (!projectId) return

    try {
      setLoading(true)
      await analysisApi.repairAnalysis(projectId, sampleId)
      message.success('分析修复任务已启动')
      await loadData(true)
    } catch (error) {
      message.error('启动分析修复失败')
    } finally {
      setLoading(false)
    }
  }

  const handleDelete = async (sampleId: string) => {
    if (!projectId) return

    Modal.confirm({
      title: '确认删除',
      content: '删除样本后将无法恢复，是否继续？',
      onOk: async () => {
        try {
          await sampleApi.delete(projectId, sampleId)
          message.success('删除成功')
          loadData()
        } catch (error) {
          message.error('删除失败')
        }
      },
    })
  }

  const handleSynthesize = async () => {
    if (!projectId) return

    const completedSamples = samples.filter((sample) => sample.status === 'ANALYZED')
    if (completedSamples.length < 2) {
      message.warning('至少需要2个已分析的样本才能进行跨书归纳')
      return
    }

    try {
      setLoading(true)
      const sampleIds = completedSamples.map((sample) => sample.id)
      await analysisApi.synthesizeBooks(projectId, sampleIds)
      message.success('跨书归纳任务已启动，完成后可生成Skills')
      await loadData(true)
    } catch (error) {
      message.error('启动跨书归纳失败')
    } finally {
      setLoading(false)
    }
  }

  const openSampleDetail = async (sample: Sample) => {
    if (!projectId) return
    setSelectedSample(sample)
    setDetailLoading(true)
    setSampleArtifacts(null)
    setChunks([])
    setBookReport(null)
    setCoverageReport(null)
    try {
      const sampleId = sample.sampleId || sample.id
      const [artifactsData, chunkData, reportData, coverageData] = await Promise.all([
        analysisApi.getSampleArtifacts(projectId, sampleId).catch(() => null),
        analysisApi.getChunks(projectId, sampleId).catch(() => []),
        analysisApi.getBookReport(projectId, sampleId).catch(() => null),
        analysisApi.getCoverage(projectId, sampleId).catch(() => null),
      ])
      setSampleArtifacts(artifactsData)
      setChunks(chunkData || [])
      setBookReport(reportData)
      setCoverageReport(coverageData || artifactsData?.coverageReport || null)
    } catch (error) {
      message.error('加载样本产物失败')
    } finally {
      setDetailLoading(false)
    }
  }

  const openCrossBookReport = async () => {
    if (!projectId) return
    try {
      const data = await analysisApi.getCrossBookReport(projectId)
      setReportDrawer({
        title: '跨书归纳报告',
        content: data.content,
        path: data.path,
        techniqueSummary: data.techniqueSummary,
      })
    } catch (error) {
      message.warning('暂无跨书归纳报告')
    }
  }

  const openTaskLogs = async (taskId: string) => {
    try {
      const data = await taskApi.getLogs(taskId)
      setTaskDrawer(data)
    } catch (error) {
      message.error('加载任务日志失败')
    }
  }

  const retryTask = async (taskId: string) => {
    try {
      await taskApi.retry(taskId)
      message.success('任务已重新提交')
      await loadData(true)
    } catch (error) {
      message.error('任务重试失败')
    }
  }

  const columns = [
    {
      title: '样本名称',
      dataIndex: 'sampleName',
      key: 'sampleName',
      render: (value: string, record: Sample) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary">{record.fileName}</Text>
        </Space>
      ),
    },
    {
      title: '章节/字数',
      key: 'size',
      render: (_: any, record: Sample) => (
        <Space direction="vertical" size={0}>
          <Text>{record.totalChapters ?? '-'} 章</Text>
          <Text type="secondary">{record.totalChars ?? '-'} 字</Text>
        </Space>
      ),
    },
    {
      title: '分块/分析',
      key: 'analysis',
      render: (_: any, record: Sample) => (
        <Space direction="vertical" size={0}>
          <Text>{record.chunkCount ?? 0} 块</Text>
          <Text type="secondary">{record.analysisCount ?? 0} 已分析</Text>
        </Space>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string, record: Sample) => {
        const activeTask = activeTaskForSample(record.sampleId || record.id)
        const effectiveStatus = activeTask?.status || status
        const statusInfo = statusMap[effectiveStatus] || { color: 'default', text: effectiveStatus }
        const percent = record.chunkCount
          ? Math.round(((record.analysisCount || 0) / record.chunkCount) * 100)
          : effectiveStatus === 'ANALYZED'
            ? 100
            : 0

        return (
          <Space direction="vertical" size="small">
            <Tag color={statusInfo.color}>{statusInfo.text}</Tag>
            {activeTask && <Progress percent={percent} size="small" />}
          </Space>
        )
      },
    },
    {
      title: '报告',
      key: 'report',
      render: (_: any, record: Sample) => (
        <Tag color={record.hasBookReport ? 'success' : 'default'}>
          {record.hasBookReport ? '已生成' : '未生成'}
        </Tag>
      ),
    },
    {
      title: '上传时间',
      dataIndex: 'uploadTime',
      key: 'uploadTime',
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: Sample) => (
        <Space size="small">
          <Button
            type="link"
            icon={<PlayCircleOutlined />}
            disabled={!['CHUNKED', 'ANALYZED'].includes(record.status)}
            onClick={() => startAnalysis(record.sampleId || record.id)}
          >
            分析
          </Button>
          <Button
            type="link"
            icon={<FileDoneOutlined />}
            disabled={record.status !== 'ANALYZED'}
            onClick={() => summarizeBook(record.sampleId || record.id)}
          >
            汇总
          </Button>
          <Button type="link" icon={<EyeOutlined />} onClick={() => openSampleDetail(record)}>
            查看
          </Button>
          <Button
            type="link"
            icon={<FileSearchOutlined />}
            disabled={!record.chunkCount}
            onClick={() => checkCoverage(record.sampleId || record.id)}
          >
            校验
          </Button>
          <Button
            type="link"
            icon={<ReloadOutlined />}
            disabled={!record.chunkCount}
            onClick={() => repairAnalysis(record.sampleId || record.id)}
          >
            修复
          </Button>
          <Button
            type="link"
            danger
            icon={<DeleteOutlined />}
            onClick={() => handleDelete(record.id)}
          >
            删除
          </Button>
        </Space>
      ),
    },
  ]

  const taskColumns = [
    { title: 'Agent', dataIndex: 'agentName', key: 'agentName' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const statusInfo = statusMap[status] || { color: 'default', text: status }
        return <Tag color={statusInfo.color}>{statusInfo.text}</Tag>
      },
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: any) => (
        <Space>
          <Button type="link" onClick={() => openTaskLogs(record.id)}>
            详情
          </Button>
          {record.status === 'FAILED' && (
            <Button type="link" onClick={() => retryTask(record.id)}>
              重试
            </Button>
          )}
        </Space>
      ),
    },
  ]

  const analyzedCount = analysisStatus?.analyzedSamples || samples.filter((sample) => sample.status === 'ANALYZED').length
  const totalSamples = analysisStatus?.sampleCount ?? samples.length
  const analysisPercent = totalSamples ? Math.round((analyzedCount / totalSamples) * 100) : 0

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card>
        <Row gutter={16}>
          <Col span={6}>
            <Statistic title="样本总数" value={totalSamples} />
          </Col>
          <Col span={6}>
            <Statistic title="已分析" value={analyzedCount} />
          </Col>
          <Col span={6}>
            <Statistic title="运行任务" value={analysisStatus?.runningTasks || 0} />
          </Col>
          <Col span={6}>
            <Statistic title="跨书归纳" value={analysisStatus?.hasCrossBookReport ? '已生成' : '未生成'} />
          </Col>
        </Row>
        <Progress percent={analysisPercent} style={{ marginTop: 16 }} />
      </Card>

      <Card
        title={
          <Space>
            <FileTextOutlined />
            样本管理
          </Space>
        }
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => loadData()}>
              刷新
            </Button>
            <Button type="primary" onClick={() => setUploadModalVisible(true)}>
              上传样本
            </Button>
            <Button
              type="default"
              onClick={handleSynthesize}
              disabled={samples.filter((sample) => sample.status === 'ANALYZED').length < 2}
            >
              跨书归纳
            </Button>
            <Button
              icon={<FileSearchOutlined />}
              disabled={!analysisStatus?.hasCrossBookReport}
              onClick={openCrossBookReport}
            >
              查看跨书报告
            </Button>
          </Space>
        }
      >
        {runningTasks && (
          <Alert
            type="info"
            showIcon
            message="有任务正在执行，页面会自动刷新状态。"
            style={{ marginBottom: 16 }}
          />
        )}
        <Table columns={columns} dataSource={samples} loading={loading} rowKey="id" />
      </Card>

      <Card title="最近任务">
        <Table
          columns={taskColumns}
          dataSource={[...tasks].sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || ''))).slice(0, 8)}
          rowKey="id"
          pagination={false}
          size="small"
        />
      </Card>

      <Modal
        title="上传样本小说"
        open={uploadModalVisible}
        onOk={() => form.submit()}
        onCancel={() => {
          setUploadModalVisible(false)
          form.resetFields()
          setFileList([])
        }}
        confirmLoading={loading}
        width={600}
      >
        <Form form={form} layout="vertical" onFinish={handleUpload}>
          <Form.Item
            name="sampleName"
            label="样本名称"
            rules={[{ required: true, message: '请输入样本名称' }]}
          >
            <Input placeholder="例如：完美世界" />
          </Form.Item>

          <Form.Item name="author" label="作者">
            <Input placeholder="例如：辰东" />
          </Form.Item>

          <Form.Item name="genre" label="类型">
            <Input placeholder="例如：玄幻" />
          </Form.Item>

          <Form.Item label="选择文件" required>
            <Dragger {...uploadProps}>
              <p className="ant-upload-drag-icon">
                <InboxOutlined />
              </p>
              <p className="ant-upload-text">点击或拖拽文件到此区域上传</p>
              <p className="ant-upload-hint">支持 TXT 和 Markdown，单个文件不超过20MB</p>
            </Dragger>
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={selectedSample?.sampleName || '样本产物'}
        width={920}
        open={!!selectedSample}
        onClose={() => setSelectedSample(null)}
        loading={detailLoading}
      >
        {selectedSample ? (
          <Tabs
            items={[
              {
                key: 'overview',
                label: '概览',
                children: sampleArtifacts ? (
                  <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                    <Descriptions bordered column={2}>
                      <Descriptions.Item label="状态">{sampleArtifacts.status}</Descriptions.Item>
                      <Descriptions.Item label="章节">{sampleArtifacts.totalChapters ?? '-'}</Descriptions.Item>
                      <Descriptions.Item label="字数">{sampleArtifacts.totalChars ?? '-'}</Descriptions.Item>
                      <Descriptions.Item label="分块">{sampleArtifacts.chunkCount ?? 0}</Descriptions.Item>
                      <Descriptions.Item label="已分析">{sampleArtifacts.analysisCount ?? 0}</Descriptions.Item>
                      <Descriptions.Item label="单书报告">
                        {sampleArtifacts.hasBookReport ? '已生成' : '未生成'}
                      </Descriptions.Item>
                      <Descriptions.Item label="manifest" span={2}>
                        {sampleArtifacts.manifestPath || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="单书报告路径" span={2}>
                        {sampleArtifacts.bookReportPath || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="覆盖率报告" span={2}>
                        {sampleArtifacts.coverageReportPath || coverageReport?.path || '-'}
                      </Descriptions.Item>
                    </Descriptions>
                    {(coverageReport || sampleArtifacts.coverageReport) && (
                      <Row gutter={16}>
                        <Col span={8}>
                          <Statistic
                            title="文本覆盖率"
                            value={Math.round(((coverageReport || sampleArtifacts.coverageReport)?.textCoverage?.coverageRatio || 0) * 100)}
                            suffix="%"
                          />
                        </Col>
                        <Col span={8}>
                          <Statistic
                            title="分析覆盖率"
                            value={Math.round(((coverageReport || sampleArtifacts.coverageReport)?.analysisCoverage?.coverageRatio || 0) * 100)}
                            suffix="%"
                          />
                        </Col>
                        <Col span={8}>
                          <Statistic
                            title="覆盖状态"
                            value={(coverageReport || sampleArtifacts.coverageReport)?.status || '-'}
                          />
                        </Col>
                      </Row>
                    )}
                    {sampleArtifacts.analysisSummary && (
                      <pre style={{ whiteSpace: 'pre-wrap' }}>
                        {JSON.stringify(sampleArtifacts.analysisSummary, null, 2)}
                      </pre>
                    )}
                  </Space>
                ) : (
                  <Empty description="暂无产物信息" />
                ),
              },
              {
                key: 'coverage',
                label: '覆盖率',
                children: coverageReport || sampleArtifacts?.coverageReport ? (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Descriptions bordered column={2}>
                      <Descriptions.Item label="状态">
                        {(coverageReport || sampleArtifacts.coverageReport).status}
                      </Descriptions.Item>
                      <Descriptions.Item label="报告路径">
                        {(coverageReport || sampleArtifacts.coverageReport).path || sampleArtifacts.coverageReportPath || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="文本覆盖率">
                        {`${Math.round(((coverageReport || sampleArtifacts.coverageReport).textCoverage?.coverageRatio || 0) * 100)}%`}
                      </Descriptions.Item>
                      <Descriptions.Item label="分析覆盖率">
                        {`${Math.round(((coverageReport || sampleArtifacts.coverageReport).analysisCoverage?.coverageRatio || 0) * 100)}%`}
                      </Descriptions.Item>
                      <Descriptions.Item label="缺失分析块">
                        {(coverageReport || sampleArtifacts.coverageReport).analysisCoverage?.missingAnalysisCount ?? 0}
                      </Descriptions.Item>
                      <Descriptions.Item label="失败分析块">
                        {(coverageReport || sampleArtifacts.coverageReport).analysisCoverage?.failedChunkCount ?? 0}
                      </Descriptions.Item>
                    </Descriptions>
                    <pre style={{ whiteSpace: 'pre-wrap' }}>
                      {JSON.stringify(coverageReport || sampleArtifacts.coverageReport, null, 2)}
                    </pre>
                  </Space>
                ) : (
                  <Empty description="暂无覆盖率报告" />
                ),
              },
              {
                key: 'manifest',
                label: 'Manifest',
                children: sampleArtifacts?.manifest ? (
                  <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(sampleArtifacts.manifest, null, 2)}</pre>
                ) : (
                  <Empty description="暂无 manifest" />
                ),
              },
              {
                key: 'chunks',
                label: '分块',
                children: chunks.length ? (
                  <List
                    dataSource={chunks}
                    renderItem={(chunk) => (
                      <List.Item>
                        <List.Item.Meta
                          title={`${chunk.id} ${chunk.chapterRange || ''}`}
                          description={chunk.preview}
                        />
                      </List.Item>
                    )}
                  />
                ) : (
                  <Empty description="暂无分块" />
                ),
              },
              {
                key: 'report',
                label: '单书报告',
                children: bookReport?.content ? (
                  <Space direction="vertical" style={{ width: '100%' }}>
                    <Text type="secondary">{bookReport.path}</Text>
                    <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{bookReport.content}</Paragraph>
                  </Space>
                ) : (
                  <Empty description="暂无单书报告" />
                ),
              },
            ]}
          />
        ) : null}
      </Drawer>

      <Drawer
        title={reportDrawer?.title || '报告'}
        width={920}
        open={!!reportDrawer}
        onClose={() => setReportDrawer(null)}
      >
        {reportDrawer?.path && <Text type="secondary">{reportDrawer.path}</Text>}
        {reportDrawer?.techniqueSummary && (
          <>
            <Title level={5}>技巧汇总</Title>
            <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(reportDrawer.techniqueSummary, null, 2)}</pre>
          </>
        )}
        <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{reportDrawer?.content}</Paragraph>
      </Drawer>

      <Drawer
        title={taskDrawer?.taskId || '任务详情'}
        width={860}
        open={!!taskDrawer}
        onClose={() => setTaskDrawer(null)}
      >
        <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(taskDrawer, null, 2)}</pre>
      </Drawer>
    </Space>
  )
}

export default SampleManagement
