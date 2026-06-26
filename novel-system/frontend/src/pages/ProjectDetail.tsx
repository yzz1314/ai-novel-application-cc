import React, { useEffect, useMemo, useState } from 'react';
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
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Spin,
  Statistic,
  Steps,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import {
  BookOutlined,
  CheckCircleOutlined,
  DatabaseOutlined,
  EditOutlined,
  EyeOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HistoryOutlined,
  FolderOpenOutlined,
  PartitionOutlined,
  PoweroffOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { bookApi, projectApi, sampleApi, skillsApi, taskApi } from '../services/api';

const { Paragraph, Text, Title } = Typography;
const { TextArea } = Input;

const ProjectDetail: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [project, setProject] = useState<any>(null);
  const [samples, setSamples] = useState<any[]>([]);
  const [tasks, setTasks] = useState<any[]>([]);
  const [skills, setSkills] = useState<any[]>([]);
  const [books, setBooks] = useState<any[]>([]);
  const [soul, setSoul] = useState<any>(null);
  const [skillDrawer, setSkillDrawer] = useState<any>(null);
  const [skillConflicts, setSkillConflicts] = useState<any>(null);
  const [skillEditOpen, setSkillEditOpen] = useState(false);
  const [skillSaving, setSkillSaving] = useState(false);
  const [editingSkill, setEditingSkill] = useState<any>(null);
  const [skillVersionOpen, setSkillVersionOpen] = useState(false);
  const [skillVersionLoading, setSkillVersionLoading] = useState(false);
  const [skillVersions, setSkillVersions] = useState<any[]>([]);
  const [versionSkill, setVersionSkill] = useState<any>(null);
  const [skillForm] = Form.useForm();

  useEffect(() => {
    loadProjectData();
  }, [projectId]);

  const loadProjectData = async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      const [projectData, sampleData, taskData, skillData, bookData, conflictData] = await Promise.all([
        projectApi.getDetail(projectId),
        sampleApi.getList(projectId).catch(() => []),
        taskApi.getList(projectId).catch(() => []),
        skillsApi.getList(projectId).catch(() => []),
        bookApi.getList(projectId).catch(() => []),
        skillsApi.getConflicts(projectId).catch(() => null),
      ]);
      setProject(projectData);
      setSamples(sampleData || []);
      setTasks(taskData || []);
      setSkills(skillData || []);
      setBooks(bookData || []);
      setSkillConflicts(conflictData);

      if ((bookData || []).length > 0) {
        const soulData = await bookApi.getSoul(projectId, 'default').catch(() => null);
        setSoul(soulData);
      } else {
        setSoul(null);
      }
    } catch (error) {
      message.error('加载项目详情失败');
    } finally {
      setLoading(false);
    }
  };

  const analyzedSamples = samples.filter((sample) => sample.status === 'ANALYZED').length;
  const hasCrossBook = tasks.some(
    (task) => task.agentName === 'cross_book_synthesis' && task.status === 'SUCCESS'
  );
  const hasSkills = skills.length > 0;
  const hasOutline = books.length > 0;
  const latestBook = books[0];

  const currentStep = useMemo(() => {
    if (hasOutline) return 4;
    if (hasSkills) return 3;
    if (hasCrossBook) return 2;
    if (analyzedSamples > 0) return 1;
    return samples.length > 0 ? 0 : 0;
  }, [analyzedSamples, hasCrossBook, hasOutline, hasSkills, samples.length]);

  const progress = Math.min(
    100,
    [samples.length > 0, analyzedSamples > 0, hasCrossBook, hasSkills, hasOutline].filter(Boolean)
      .length * 20
  );

  const workflowSteps = [
    {
      title: '上传样本',
      status: samples.length > 0 ? 'finish' : 'process',
      icon: <FileTextOutlined />,
    },
    {
      title: '分析样本',
      status: analyzedSamples > 0 ? 'finish' : samples.length > 0 ? 'process' : 'wait',
      icon: <FileSearchOutlined />,
    },
    {
      title: '跨书归纳',
      status: hasCrossBook ? 'finish' : analyzedSamples >= 2 ? 'process' : 'wait',
      icon: <CheckCircleOutlined />,
    },
    {
      title: '生成Skills',
      status: hasSkills ? 'finish' : hasCrossBook ? 'process' : 'wait',
      icon: <CheckCircleOutlined />,
    },
    {
      title: '规划大纲',
      status: hasOutline ? 'finish' : hasSkills ? 'process' : 'wait',
      icon: <BookOutlined />,
    },
  ];

  const recentTasks = [...tasks]
    .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')))
    .slice(0, 8);

  const conflictCount = skillConflicts?.conflictCount || 0;

  const qualityColor = (status?: string) => {
    if (status === 'passed') return 'success';
    if (status === 'needs_review') return 'warning';
    if (status === 'failed') return 'error';
    return 'default';
  };

  const approvalColor = (status?: string) => {
    if (status === 'approved') return 'success';
    if (status === 'rejected') return 'error';
    return 'processing';
  };

  const skillColumns = [
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '类型', dataIndex: 'type', key: 'type', render: (type: string) => <Tag>{type}</Tag> },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => <Tag color={enabled ? 'success' : 'default'}>{enabled ? '启用' : '停用'}</Tag>,
    },
    {
      title: '质量',
      key: 'quality',
      width: 130,
      render: (_: any, record: any) => (
        <Space size={4} direction="vertical">
          <Tag color={qualityColor(record.qualityStatus)}>{record.qualityStatus || 'unchecked'}</Tag>
          <Text type="secondary">{record.qualityScore !== null && record.qualityScore !== undefined ? `${record.qualityScore}分` : '未校验'}</Text>
        </Space>
      ),
    },
    {
      title: '审批',
      dataIndex: 'approvalStatus',
      key: 'approvalStatus',
      width: 110,
      render: (status: string) => <Tag color={approvalColor(status)}>{status || 'pending'}</Tag>,
    },
    { title: '优先级', dataIndex: 'priority', key: 'priority', width: 90 },
    {
      title: '操作',
      key: 'action',
      width: 360,
      render: (_: any, record: any) => (
        <Space size="small" wrap>
          <Button type="link" icon={<EyeOutlined />} onClick={() => loadSkillContent(record.name)}>
            查看
          </Button>
          <Button type="link" icon={<EditOutlined />} onClick={() => openSkillEditor(record.name)}>
            编辑
          </Button>
          <Button type="link" icon={<SafetyCertificateOutlined />} onClick={() => checkSkillQuality(record)}>
            校验
          </Button>
          <Popconfirm
            title="批准这个Skill？"
            description="批准后会保持启用，并记录审批报告。"
            okText="批准"
            cancelText="取消"
            onConfirm={() => approveSkill(record)}
          >
            <Button type="link">批准</Button>
          </Popconfirm>
          <Popconfirm
            title="驳回这个Skill？"
            description="驳回后会停用，并记录驳回报告。"
            okText="驳回"
            cancelText="取消"
            onConfirm={() => rejectSkill(record)}
          >
            <Button type="link" danger>驳回</Button>
          </Popconfirm>
          <Button type="link" icon={<HistoryOutlined />} onClick={() => openSkillVersions(record)}>
            版本
          </Button>
          <Button
            type="link"
            icon={<PoweroffOutlined />}
            onClick={() => toggleSkill(record)}
          >
            {record.enabled ? '停用' : '启用'}
          </Button>
        </Space>
      ),
    },
  ];

  const taskColumns = [
    { title: 'Agent', dataIndex: 'agentName', key: 'agentName' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status: string) => {
        const color = status === 'SUCCESS' ? 'success' : status === 'FAILED' ? 'error' : 'processing';
        return <Tag color={color}>{status}</Tag>;
      },
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
  ];

  const loadSkillContent = async (skillName: string) => {
    if (!projectId) return;
    try {
      const data = await skillsApi.getContent(projectId, skillName);
      setSkillDrawer(data);
    } catch (error) {
      message.error('加载Skill失败');
    }
  };

  const openSkillEditor = async (skillName: string) => {
    if (!projectId) return;
    try {
      const data = await skillsApi.getContent(projectId, skillName);
      setEditingSkill(data);
      skillForm.setFieldsValue({
        enabled: data.enabled ?? true,
        priority: data.priority ?? 50,
        type: data.type || 'general',
        scope: data.scope || [],
        content: data.content || '',
        editNote: '',
      });
      setSkillEditOpen(true);
    } catch (error) {
      message.error('加载Skill失败');
    }
  };

  const normalizeScope = (scope: any[] = []) =>
    scope.map((item) => String(item).trim()).filter(Boolean);

  const sameScope = (left: any[] = [], right: any[] = []) => {
    const leftScope = normalizeScope(left);
    const rightScope = normalizeScope(right);
    return leftScope.length === rightScope.length && leftScope.every((item, index) => item === rightScope[index]);
  };

  const saveSkillEditor = async () => {
    if (!projectId || !editingSkill) return;
    try {
      const values = await skillForm.validateFields();
      setSkillSaving(true);
      const editNote = values.editNote || '';
      const contentChanged = values.content !== editingSkill.content;
      const configChanged =
        values.enabled !== editingSkill.enabled ||
        values.priority !== editingSkill.priority ||
        values.type !== editingSkill.type ||
        !sameScope(values.scope, editingSkill.scope);

      if (contentChanged) {
        await skillsApi.update(projectId, editingSkill.name, {
          content: values.content,
          editNote,
          editor: 'human',
        });
      }
      if (configChanged) {
        await skillsApi.updateConfig(projectId, editingSkill.name, {
          enabled: values.enabled,
          priority: values.priority,
          type: values.type,
          scope: normalizeScope(values.scope),
          editNote,
          editor: 'human',
        });
      }
      if (!contentChanged && !configChanged) {
        message.info('Skill未发生变化');
      } else {
        message.success('Skill已保存');
      }
      setSkillEditOpen(false);
      setEditingSkill(null);
      await loadProjectData();
    } catch (error) {
      if (error instanceof Error) {
        message.error('保存Skill失败');
      }
    } finally {
      setSkillSaving(false);
    }
  };

  const toggleSkill = async (skill: any) => {
    if (!projectId) return;
    try {
      if (skill.enabled) {
        await skillsApi.disable(projectId, skill.name);
        message.success('Skill已停用');
      } else {
        await skillsApi.enable(projectId, skill.name);
        message.success('Skill已启用');
      }
      await loadProjectData();
    } catch (error) {
      message.error('更新Skill状态失败');
    }
  };

  const checkSkillQuality = async (skill: any) => {
    if (!projectId) return;
    try {
      const result: any = await skillsApi.checkQuality(projectId, skill.name, { checkedBy: 'human' });
      message.success(`质量校验完成：${result.status}，${result.score}分`);
      await loadProjectData();
    } catch (error) {
      message.error('质量校验失败');
    }
  };

  const approveSkill = async (skill: any) => {
    if (!projectId) return;
    try {
      await skillsApi.approve(projectId, skill.name, {
        reviewer: 'human',
        note: 'ProjectDetail批准',
        enabled: true,
      });
      message.success('Skill已批准');
      await loadProjectData();
    } catch (error) {
      message.error('批准Skill失败');
    }
  };

  const rejectSkill = async (skill: any) => {
    if (!projectId) return;
    try {
      await skillsApi.reject(projectId, skill.name, {
        reviewer: 'human',
        reason: 'ProjectDetail驳回',
        enabled: false,
      });
      message.success('Skill已驳回并停用');
      await loadProjectData();
    } catch (error) {
      message.error('驳回Skill失败');
    }
  };

  const openSkillVersions = async (skill: any) => {
    if (!projectId) return;
    try {
      setVersionSkill(skill);
      setSkillVersionOpen(true);
      setSkillVersionLoading(true);
      const data = await skillsApi.getVersions(projectId, skill.name);
      setSkillVersions(data || []);
    } catch (error) {
      message.error('加载Skill版本失败');
    } finally {
      setSkillVersionLoading(false);
    }
  };

  const restoreSkillVersion = async (versionId: string) => {
    if (!projectId || !versionSkill) return;
    try {
      setSkillVersionLoading(true);
      await skillsApi.restoreVersion(projectId, versionSkill.name, versionId, {
        restorer: 'human',
        note: 'restore from ProjectDetail',
        createVersionSnapshot: true,
      });
      message.success('Skill版本已恢复');
      const data = await skillsApi.getVersions(projectId, versionSkill.name);
      setSkillVersions(data || []);
      await loadProjectData();
    } catch (error) {
      message.error('恢复Skill版本失败');
    } finally {
      setSkillVersionLoading(false);
    }
  };

  const tabItems = [
    {
      key: 'overview',
      label: '概览',
      children: (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          <Row gutter={16}>
            <Col span={6}><Card><Statistic title="样本" value={samples.length} /></Card></Col>
            <Col span={6}><Card><Statistic title="已分析" value={analyzedSamples} /></Card></Col>
            <Col span={6}><Card><Statistic title="Skills" value={skills.length} /></Card></Col>
            <Col span={6}><Card><Statistic title="书籍" value={books.length} /></Card></Col>
          </Row>
          <Card>
            <Title level={4}>项目进度</Title>
            <Progress percent={progress} />
            <Alert
              style={{ marginTop: 16 }}
              message={hasOutline ? '已生成大纲和Project Soul' : hasSkills ? '可以进入大纲生成' : '继续完成样本分析与Skill生成'}
              type={hasOutline ? 'success' : 'info'}
              showIcon
            />
          </Card>
          <Table columns={taskColumns} dataSource={recentTasks} rowKey="id" pagination={false} />
        </Space>
      ),
    },
    {
      key: 'skills',
      label: <span><CheckCircleOutlined /> Skills</span>,
      children: skills.length ? (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {skillConflicts && (
            conflictCount > 0 ? (
              <Alert
                type="warning"
                showIcon
                message={`检测到 ${conflictCount} 个Skill冲突或路由风险`}
                description={
                  <List
                    size="small"
                    dataSource={(skillConflicts.conflicts || []).slice(0, 5)}
                    renderItem={(item: any) => (
                      <List.Item>
                        <Space size="small" wrap>
                          <Tag color={item.severity === 'error' ? 'error' : item.severity === 'warning' ? 'warning' : 'default'}>
                            {item.severity}
                          </Tag>
                          <Text>{item.skillA} / {item.skillB}</Text>
                          <Text type="secondary">{item.message}</Text>
                        </Space>
                      </List.Item>
                    )}
                  />
                }
              />
            ) : (
              <Alert type="success" showIcon message="当前启用Skill未检测到冲突" />
            )
          )}
          <Table columns={skillColumns} dataSource={skills} rowKey="name" pagination={false} />
        </Space>
      ) : (
        <Empty description="暂无项目Skill" />
      ),
    },
    {
      key: 'outline',
      label: <span><BookOutlined /> 大纲</span>,
      children: latestBook ? (
        <Card>
          <Descriptions column={2} bordered>
            <Descriptions.Item label="书名">{latestBook.bookTitle}</Descriptions.Item>
            <Descriptions.Item label="类型">{latestBook.genre}</Descriptions.Item>
            <Descriptions.Item label="卷数">{latestBook.totalVolumes}</Descriptions.Item>
            <Descriptions.Item label="章节数">{latestBook.totalChapters}</Descriptions.Item>
            <Descriptions.Item label="大纲路径" span={2}>{latestBook.outlinePath}</Descriptions.Item>
            <Descriptions.Item label="Project Soul" span={2}>{latestBook.projectSoulPath}</Descriptions.Item>
          </Descriptions>
          {soul?.content && (
            <Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 16, maxHeight: 320, overflow: 'auto' }}>
              {soul.content}
            </Paragraph>
          )}
        </Card>
      ) : (
        <Empty description="暂无大纲" />
      ),
    },
    {
      key: 'chapters',
      label: <span><EditOutlined /> 章节</span>,
      children: latestBook ? (
        <Button type="primary" onClick={() => navigate(`/projects/${projectId}/chapters`)}>
          打开章节创作
        </Button>
      ) : (
        <Empty description="先生成大纲后再创作章节" />
      ),
    },
    {
      key: 'memory',
      label: <span><DatabaseOutlined /> 记忆</span>,
      children: (
        <Button icon={<DatabaseOutlined />} onClick={() => navigate(`/projects/${projectId}/memory`)}>
          打开记忆管理
        </Button>
      ),
    },
    {
      key: 'graph',
      label: <span><PartitionOutlined /> 图谱</span>,
      children: (
        <Button icon={<PartitionOutlined />} onClick={() => navigate(`/projects/${projectId}/graph`)}>
          打开知识图谱
        </Button>
      ),
    },
    {
      key: 'retrieval',
      label: <span><SearchOutlined /> 检索</span>,
      children: (
        <Button icon={<SearchOutlined />} onClick={() => navigate(`/projects/${projectId}/retrieval`)}>
          打开检索与上下文
        </Button>
      ),
    },
    {
      key: 'artifacts',
      label: <span><FolderOpenOutlined /> 产物</span>,
      children: (
        <Button icon={<FolderOpenOutlined />} onClick={() => navigate(`/projects/${projectId}/artifacts`)}>
          打开产物管理
        </Button>
      ),
    },
  ];

  return (
    <Spin spinning={loading}>
      <Space direction="vertical" size="large" style={{ width: '100%' }}>
        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space align="start" style={{ justifyContent: 'space-between', width: '100%' }}>
              <div>
                <Title level={3} style={{ marginBottom: 0 }}>{project?.name || projectId}</Title>
                <Text type="secondary">{project?.description || '暂无描述'}</Text>
              </div>
              <Button icon={<ReloadOutlined />} onClick={loadProjectData}>刷新</Button>
            </Space>
            <Steps current={currentStep} items={workflowSteps as any} />
          </Space>
        </Card>

        <Card>
          <Tabs defaultActiveKey="overview" items={tabItems} />
        </Card>
      </Space>

      <Drawer
        title={skillDrawer?.title || skillDrawer?.name || 'Skill'}
        width={760}
        open={!!skillDrawer}
        onClose={() => setSkillDrawer(null)}
      >
        <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{skillDrawer?.content}</Paragraph>
      </Drawer>

      <Modal
        title={editingSkill?.title || editingSkill?.name || '编辑Skill'}
        open={skillEditOpen}
        width={900}
        confirmLoading={skillSaving}
        onOk={saveSkillEditor}
        onCancel={() => {
          setSkillEditOpen(false);
          setEditingSkill(null);
        }}
        okText="保存"
        cancelText="取消"
      >
        <Form form={skillForm} layout="vertical">
          <Row gutter={16}>
            <Col span={6}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="priority" label="优先级">
                <InputNumber min={0} max={999} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="type" label="类型">
                <Input />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="scope" label="作用域">
            <Select mode="tags" tokenSeparators={[',']} placeholder="输入scope后回车" />
          </Form.Item>
          <Form.Item name="editNote" label="编辑说明">
            <Input placeholder="记录本次调整原因" />
          </Form.Item>
          <Form.Item
            name="content"
            label="Skill内容"
            rules={[{ required: true, message: 'Skill内容不能为空' }]}
          >
            <TextArea rows={18} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`${versionSkill?.title || versionSkill?.name || 'Skill'} 历史版本`}
        open={skillVersionOpen}
        width={860}
        footer={null}
        onCancel={() => {
          setSkillVersionOpen(false);
          setVersionSkill(null);
          setSkillVersions([]);
        }}
      >
        <Table
          loading={skillVersionLoading}
          dataSource={skillVersions}
          rowKey="id"
          pagination={{ pageSize: 6 }}
          columns={[
            { title: '版本ID', dataIndex: 'id', key: 'id', ellipsis: true },
            { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
            { title: '归档时间', dataIndex: 'archivedAt', key: 'archivedAt', width: 190 },
            { title: '大小', dataIndex: 'sizeBytes', key: 'sizeBytes', width: 90 },
            {
              title: '操作',
              key: 'action',
              width: 90,
              render: (_: any, record: any) => (
                <Button type="link" onClick={() => restoreSkillVersion(record.id)}>
                  恢复
                </Button>
              ),
            },
          ]}
          locale={{ emptyText: '暂无历史版本' }}
        />
      </Modal>
    </Spin>
  );
};

export default ProjectDetail;
