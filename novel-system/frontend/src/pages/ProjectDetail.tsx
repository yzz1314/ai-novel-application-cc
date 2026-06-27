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
  CloseCircleOutlined,
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

const workflowTemplates = [
  {
    id: 'sample_analysis',
    title: '样本分析流水线',
    description: '对单个样本依次执行全文分析、覆盖率检查和单书总结。',
    needs: 'sample',
  },
  {
    id: 'parallel_sample_analysis',
    title: '并行双样本分析',
    description: '并行分析两个样本，成功后自动执行跨书归纳。',
    needs: 'twoSamples',
  },
  {
    id: 'memory_graph_refresh',
    title: '记忆与图谱刷新',
    description: '先摄取章节记忆，再并行重建图谱和检索索引。',
    needs: 'book',
  },
  {
    id: 'chapter_pipeline',
    title: '章节创作流水线',
    description: '生成章节、按边界条件返修，等待人工确认后摄取记忆。',
    needs: 'chapter',
  },
];

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
  const [skillConflictReportLoading, setSkillConflictReportLoading] = useState(false);
  const [skillRegenerating, setSkillRegenerating] = useState(false);
  const [skillEditOpen, setSkillEditOpen] = useState(false);
  const [skillSaving, setSkillSaving] = useState(false);
  const [editingSkill, setEditingSkill] = useState<any>(null);
  const [skillVersionOpen, setSkillVersionOpen] = useState(false);
  const [skillVersionLoading, setSkillVersionLoading] = useState(false);
  const [skillVersions, setSkillVersions] = useState<any[]>([]);
  const [versionSkill, setVersionSkill] = useState<any>(null);
  const [skillVersionDiff, setSkillVersionDiff] = useState<any>(null);
  const [skillVersionDiffLoading, setSkillVersionDiffLoading] = useState(false);
  const [skillConfigVersionOpen, setSkillConfigVersionOpen] = useState(false);
  const [skillConfigVersionLoading, setSkillConfigVersionLoading] = useState(false);
  const [skillConfigVersions, setSkillConfigVersions] = useState<any[]>([]);
  const [skillQualityDetail, setSkillQualityDetail] = useState<any>(null);
  const [approvalTask, setApprovalTask] = useState<any>(null);
  const [approvalDecision, setApprovalDecision] = useState<'approve' | 'reject'>('approve');
  const [approvalSubmitting, setApprovalSubmitting] = useState(false);
  const [startingWorkflow, setStartingWorkflow] = useState<string | null>(null);
  const [workflowForm] = Form.useForm();
  const [skillForm] = Form.useForm();
  const [approvalForm] = Form.useForm();

  useEffect(() => {
    loadProjectData();
  }, [projectId]);

  const loadProjectData = async (silent = false) => {
    if (!projectId) return;
    try {
      if (!silent) setLoading(true);
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
      if (!silent) setLoading(false);
    }
  };

  const analyzedSamples = samples.filter((sample) => sample.status === 'ANALYZED').length;
  const hasCrossBook = tasks.some(
    (task) => task.agentName === 'cross_book_synthesis' && task.status === 'SUCCESS'
  );
  const hasSkills = skills.length > 0;
  const hasOutline = books.length > 0;
  const latestBook = books[0];

  useEffect(() => {
    const current = workflowForm.getFieldsValue();
    const sampleIds = samples.map((sample) => sample.id);
    const bookIds = (books.length ? books : [{ bookId: 'default' }]).map((book) => book.bookId || 'default');
    workflowForm.setFieldsValue({
      sampleId: sampleIds.includes(current.sampleId) ? current.sampleId : samples[0]?.id,
      sampleIdA: sampleIds.includes(current.sampleIdA) ? current.sampleIdA : samples[0]?.id,
      sampleIdB: sampleIds.includes(current.sampleIdB) ? current.sampleIdB : samples[1]?.id,
      bookId: bookIds.includes(current.bookId) ? current.bookId : latestBook?.bookId || 'default',
    });
  }, [books, latestBook?.bookId, samples, workflowForm]);

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
  const latestSkillGenerationTask = [...tasks]
    .filter((task) => task.agentName === 'skill_generation' || task.taskType === 'skill_generation')
    .sort((a, b) => String(b.createdAt || '').localeCompare(String(a.createdAt || '')))[0];
  const analyzedSampleIds = samples
    .filter((sample) => sample.status === 'ANALYZED')
    .map((sample) => sample.id || sample.sampleId)
    .filter(Boolean);
  const waitingApprovalTasks = tasks.filter((task) => isWaitingForHuman(task));
  const hasRunningTasks = tasks.some((task) => ['PENDING', 'RUNNING'].includes(task.status));

  useEffect(() => {
    if (!projectId || !hasRunningTasks) return;
    const timer = window.setInterval(() => loadProjectData(true), 3000);
    return () => window.clearInterval(timer);
  }, [projectId, hasRunningTasks]);

  const conflictCount = skillConflicts?.conflictCount || 0;

  const generateSkillConflictReport = async () => {
    if (!projectId) return;
    try {
      setSkillConflictReportLoading(true);
      const result: any = await skillsApi.generateConflictReport(projectId, { checkedBy: 'human' });
      setSkillConflicts(result);
      message.success(`Skill冲突报告已生成：${result.reportPath || '-'}`);
    } catch (error) {
      message.error('生成Skill冲突报告失败');
    } finally {
      setSkillConflictReportLoading(false);
    }
  };

  const regenerateSkills = async () => {
    if (!projectId) return;
    try {
      setSkillRegenerating(true);
      await skillsApi.generate(projectId, {
        project_id: projectId,
        regenerate: true,
        skill_types: ['writing', 'outline', 'review'],
        sample_ids: analyzedSampleIds,
        previous_skill_count: skills.length,
        requested_by: 'ProjectDetail',
      });
      message.success('Skill重新生成任务已创建');
      await loadProjectData();
    } catch (error) {
      message.error('创建Skill重新生成任务失败');
    } finally {
      setSkillRegenerating(false);
    }
  };

  function isWaitingForHuman(task: any) {
    return task?.status === 'PARTIAL' && !!task?.result?.waiting_for_human?.node_id;
  }

  const taskStatusColor = (status?: string) => {
    if (status === 'SUCCESS') return 'success';
    if (status === 'FAILED') return 'error';
    if (status === 'PARTIAL') return 'warning';
    if (status === 'CANCELLED') return 'default';
    return 'processing';
  };

  const qualityColor = (status?: string) => {
    if (status === 'passed') return 'success';
    if (status === 'needs_review') return 'warning';
    if (status === 'failed') return 'error';
    return 'default';
  };

  const showSkillQualityDetail = (skill: any, result?: any) => {
    setSkillQualityDetail({
      skillName: skill?.name || result?.skill?.name,
      status: result?.status || skill?.qualityStatus,
      score: result?.score ?? skill?.qualityScore,
      reportPath: result?.reportPath || skill?.latestQualityReportPath,
      checkedAt: result?.checkedAt || skill?.qualityCheckedAt,
      semanticQuality: result?.semanticQuality || skill?.semanticQuality || {},
    });
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
          {record.semanticQuality?.score !== undefined && (
            <Button type="link" size="small" onClick={() => showSkillQualityDetail(record)}>
              语义 {record.semanticQuality.score}
            </Button>
          )}
        </Space>
      ),
    },
    {
      title: '来源',
      key: 'sourceTrace',
      width: 110,
      render: (_: any, record: any) => {
        const sourceCount = (record.sourceTrace || []).length;
        const evidenceCount = (record.evidenceItems || []).length;
        return (
          <Space size={4} direction="vertical">
            <Tag color={sourceCount ? 'blue' : 'default'}>{sourceCount}来源</Tag>
            <Text type="secondary">{evidenceCount}证据</Text>
          </Space>
        );
      },
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
        return <Tag color={taskStatusColor(status)}>{status}</Tag>;
      },
    },
    {
      title: '当前节点',
      key: 'waitingForHuman',
      render: (_: any, record: any) => {
        const waiting = record.result?.waiting_for_human;
        if (!waiting) {
          return <Text type="secondary">{record.result?.workflow_name || '-'}</Text>;
        }
        return (
          <Space direction="vertical" size={0}>
            <Text strong>{waiting.node_id}</Text>
            <Text type="secondary">{waiting.prompt}</Text>
          </Space>
        );
      },
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_: any, record: any) => (
        isWaitingForHuman(record) ? (
          <Space size="small">
            <Button
              type="link"
              icon={<CheckCircleOutlined />}
              onClick={() => openApprovalModal(record, 'approve')}
            >
              批准
            </Button>
            <Button
              type="link"
              danger
              icon={<CloseCircleOutlined />}
              onClick={() => openApprovalModal(record, 'reject')}
            >
              驳回
            </Button>
          </Space>
        ) : (
          <Text type="secondary">-</Text>
        )
      ),
    },
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
      showSkillQualityDetail(skill, result);
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

  const diffSkillVersion = async (versionId: string) => {
    if (!projectId || !versionSkill) return;
    try {
      setSkillVersionDiffLoading(true);
      setSkillVersionDiff(null);
      const result = await skillsApi.diffVersion(projectId, versionSkill.name, versionId);
      setSkillVersionDiff(result);
    } catch (error) {
      message.error('加载Skill版本差异失败');
    } finally {
      setSkillVersionDiffLoading(false);
    }
  };

  const openSkillConfigVersions = async () => {
    if (!projectId) return;
    try {
      setSkillConfigVersionOpen(true);
      setSkillConfigVersionLoading(true);
      const data = await skillsApi.getEnabledVersions(projectId);
      setSkillConfigVersions(data || []);
    } catch (error) {
      message.error('加载Skill启用配置版本失败');
    } finally {
      setSkillConfigVersionLoading(false);
    }
  };

  const restoreSkillConfigVersion = async (versionId: string) => {
    if (!projectId) return;
    try {
      setSkillConfigVersionLoading(true);
      await skillsApi.restoreEnabledVersion(projectId, versionId, {
        restorer: 'human',
        note: 'restore enabled skill config from ProjectDetail',
        createVersionSnapshot: true,
      });
      message.success('Skill启用配置已恢复');
      const data = await skillsApi.getEnabledVersions(projectId);
      setSkillConfigVersions(data || []);
      await loadProjectData();
    } catch (error) {
      message.error('恢复Skill启用配置失败');
    } finally {
      setSkillConfigVersionLoading(false);
    }
  };

  const openApprovalModal = (task: any, decision: 'approve' | 'reject') => {
    const waiting = task?.result?.waiting_for_human;
    if (!waiting?.node_id) {
      message.warning('这个任务没有等待人工确认的节点');
      return;
    }
    setApprovalTask(task);
    setApprovalDecision(decision);
    approvalForm.setFieldsValue({
      reviewer: 'human',
      note: '',
    });
  };

  const submitWorkflowApproval = async () => {
    if (!approvalTask) return;
    const waiting = approvalTask.result?.waiting_for_human;
    if (!waiting?.node_id) {
      message.warning('这个任务没有等待人工确认的节点');
      return;
    }

    try {
      const values = await approvalForm.validateFields();
      setApprovalSubmitting(true);
      await taskApi.resume(approvalTask.id, {
        human_confirmations: {
          [waiting.node_id]: {
            approved: approvalDecision === 'approve',
            reviewer: values.reviewer || 'human',
            note: values.note || '',
            decided_at: new Date().toISOString(),
          },
        },
      });
      message.success(approvalDecision === 'approve' ? '已批准，工作流恢复执行' : '已驳回，工作流将取消');
      setApprovalTask(null);
      await loadProjectData();
    } catch (error) {
      message.error('提交工作流审批失败');
    } finally {
      setApprovalSubmitting(false);
    }
  };

  const startWorkflow = async (workflowId: string) => {
    if (!projectId) return;
    try {
      const values = await workflowForm.validateFields();
      setStartingWorkflow(workflowId);
      const inputRefs: any = {};
      const parameters: any = {
        workflow_id: workflowId,
        project_id: projectId,
      };

      if (workflowId === 'sample_analysis') {
        inputRefs.sample_id = values.sampleId;
      } else if (workflowId === 'parallel_sample_analysis') {
        inputRefs.sample_id_a = values.sampleIdA;
        inputRefs.sample_id_b = values.sampleIdB;
      } else if (workflowId === 'memory_graph_refresh') {
        inputRefs.book_id = values.bookId || latestBook?.bookId || 'default';
      } else if (workflowId === 'chapter_pipeline') {
        inputRefs.book_id = values.bookId || latestBook?.bookId || 'default';
        inputRefs.volume_number = Number(values.volumeNumber || 1);
        inputRefs.chapter_number = Number(values.chapterNumber || 1);
      }

      await taskApi.execute(projectId, {
        agentName: 'workflow',
        taskType: 'workflow',
        inputRefs,
        parameters,
      });
      message.success('工作流任务已创建');
      await loadProjectData();
    } catch (error) {
      message.error('启动工作流失败');
    } finally {
      setStartingWorkflow(null);
    }
  };

  const tabItems = [
    {
      key: 'overview',
      label: '概览',
      children: (
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          {waitingApprovalTasks.length > 0 && (
            <Alert
              type="warning"
              showIcon
              message={`有 ${waitingApprovalTasks.length} 个工作流等待人工确认`}
              description={
                <List
                  size="small"
                  dataSource={waitingApprovalTasks.slice(0, 3)}
                  renderItem={(task: any) => (
                    <List.Item
                      actions={[
                        <Button
                          key="approve"
                          size="small"
                          type="primary"
                          icon={<CheckCircleOutlined />}
                          onClick={() => openApprovalModal(task, 'approve')}
                        >
                          批准
                        </Button>,
                        <Button
                          key="reject"
                          size="small"
                          danger
                          icon={<CloseCircleOutlined />}
                          onClick={() => openApprovalModal(task, 'reject')}
                        >
                          驳回
                        </Button>,
                      ]}
                    >
                      <List.Item.Meta
                        title={`${task.agentName || task.taskType} / ${task.result?.waiting_for_human?.node_id}`}
                        description={task.result?.waiting_for_human?.prompt || '等待人工确认'}
                      />
                    </List.Item>
                  )}
                />
              }
            />
          )}
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
      key: 'workflow',
      label: <span><PartitionOutlined /> 工作流</span>,
      children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type="info"
            showIcon
            message="内置工作流会通过统一任务中心运行，遇到人工确认节点会在本页和任务中心等待审批。"
          />
          <Form
            form={workflowForm}
            layout="vertical"
            initialValues={{
              sampleId: samples[0]?.id,
              sampleIdA: samples[0]?.id,
              sampleIdB: samples[1]?.id,
              bookId: latestBook?.bookId || 'default',
              volumeNumber: 1,
              chapterNumber: 1,
            }}
          >
            <Row gutter={16}>
              <Col span={6}>
                <Form.Item name="sampleId" label="单样本">
                  <Select
                    placeholder="选择样本"
                    options={samples.map((sample) => ({ label: sample.title || sample.fileName || sample.id, value: sample.id }))}
                  />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="sampleIdA" label="样本A">
                  <Select
                    placeholder="选择样本A"
                    options={samples.map((sample) => ({ label: sample.title || sample.fileName || sample.id, value: sample.id }))}
                  />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="sampleIdB" label="样本B">
                  <Select
                    placeholder="选择样本B"
                    options={samples.map((sample) => ({ label: sample.title || sample.fileName || sample.id, value: sample.id }))}
                  />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="bookId" label="书籍">
                  <Select
                    placeholder="选择书籍"
                    options={(books.length ? books : [{ bookId: 'default', bookTitle: 'default' }]).map((book) => ({
                      label: book.bookTitle || book.bookId,
                      value: book.bookId || 'default',
                    }))}
                  />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="volumeNumber" label="卷号">
                  <InputNumber min={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col span={6}>
                <Form.Item name="chapterNumber" label="章节号">
                  <InputNumber min={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>
          </Form>
          <Row gutter={[16, 16]}>
            {workflowTemplates.map((template) => {
              const disabled =
                (template.needs === 'sample' && samples.length < 1) ||
                (template.needs === 'twoSamples' && samples.length < 2) ||
                ((template.needs === 'book' || template.needs === 'chapter') && !latestBook);
              return (
                <Col span={12} key={template.id}>
                  <Card size="small" title={template.title}>
                    <Space direction="vertical" size="small" style={{ width: '100%' }}>
                      <Text type="secondary">{template.description}</Text>
                      <Button
                        type="primary"
                        loading={startingWorkflow === template.id}
                        disabled={disabled}
                        onClick={() => startWorkflow(template.id)}
                      >
                        启动
                      </Button>
                    </Space>
                  </Card>
                </Col>
              );
            })}
          </Row>
        </Space>
      ),
    },
    {
      key: 'skills',
      label: <span><CheckCircleOutlined /> Skills</span>,
      children: (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <Button
              type="primary"
              icon={<ReloadOutlined />}
              loading={skillRegenerating}
              disabled={!hasCrossBook}
              onClick={regenerateSkills}
            >
              重新生成Skills
            </Button>
            <Button icon={<HistoryOutlined />} onClick={openSkillConfigVersions}>
              启用配置版本
            </Button>
            <Button
              icon={<FileSearchOutlined />}
              loading={skillConflictReportLoading}
              onClick={generateSkillConflictReport}
            >
              生成冲突报告
            </Button>
          </Space>
          {!hasCrossBook && (
            <Alert
              type="info"
              showIcon
              message="需要先完成跨书归纳，才能生成或重新生成项目 Skills。"
            />
          )}
          {latestSkillGenerationTask && (
            <Alert
              type={latestSkillGenerationTask.status === 'FAILED' ? 'error' : latestSkillGenerationTask.status === 'SUCCESS' ? 'success' : 'info'}
              showIcon
              message="最近一次Skill生成任务"
              description={
                <Space size="small" wrap>
                  <Tag color={taskStatusColor(latestSkillGenerationTask.status)}>{latestSkillGenerationTask.status}</Tag>
                  <Text>{latestSkillGenerationTask.id}</Text>
                  {latestSkillGenerationTask.createdAt && <Text type="secondary">{latestSkillGenerationTask.createdAt}</Text>}
                  {latestSkillGenerationTask.result?.skill_count !== undefined && (
                    <Text type="secondary">生成 {latestSkillGenerationTask.result.skill_count} 个 Skill</Text>
                  )}
                  {latestSkillGenerationTask.errorMessage && (
                    <Text type="danger">{latestSkillGenerationTask.errorMessage}</Text>
                  )}
                </Space>
              }
            />
          )}
          {skillConflicts && (
            conflictCount > 0 ? (
              <Alert
                type="warning"
                showIcon
                message={`检测到 ${conflictCount} 个Skill冲突或路由风险`}
                description={
                  <Space direction="vertical" size="small" style={{ width: '100%' }}>
                    {(skillConflicts.reportPath || skillConflicts.latestConflictReportPath) && (
                      <Text type="secondary">
                        报告：{skillConflicts.reportPath || skillConflicts.latestConflictReportPath}
                      </Text>
                    )}
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
                            {item.suggestion && <Text type="secondary">建议：{item.suggestion}</Text>}
                          </Space>
                        </List.Item>
                      )}
                    />
                  </Space>
                }
              />
            ) : (
              <Alert
                type="success"
                showIcon
                message="当前启用Skill未检测到冲突"
                description={(skillConflicts.reportPath || skillConflicts.latestConflictReportPath)
                  ? `报告：${skillConflicts.reportPath || skillConflicts.latestConflictReportPath}`
                  : undefined}
              />
            )
          )}
          {skills.length ? (
            <Table columns={skillColumns} dataSource={skills} rowKey="name" pagination={false} />
          ) : (
            <Empty description="暂无项目Skill" />
          )}
        </Space>
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
              <Button icon={<ReloadOutlined />} onClick={() => loadProjectData()}>刷新</Button>
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
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {(skillDrawer?.sourceTrace || []).length > 0 && (
            <Card title="来源追踪" size="small">
              <List
                size="small"
                dataSource={skillDrawer.sourceTrace || []}
                renderItem={(item: any) => (
                  <List.Item>
                    <Space size="small" wrap>
                      <Tag>{item.type}</Tag>
                      <Text>{item.name}</Text>
                      {item.path && <Text type="secondary">{item.path}</Text>}
                      {item.evidence && <Text type="secondary">{item.evidence}</Text>}
                    </Space>
                  </List.Item>
                )}
              />
            </Card>
          )}
          {(skillDrawer?.evidenceItems || []).length > 0 && (
            <Card title="证据片段" size="small">
              <List
                size="small"
                dataSource={(skillDrawer.evidenceItems || []).slice(0, 8)}
                renderItem={(item: any) => (
                  <List.Item>
                    <Space direction="vertical" size={2}>
                      <Space size="small" wrap>
                        <Tag color="blue">{item.type}</Tag>
                        {item.path && <Text type="secondary">{item.path}{item.line ? `:${item.line}` : ''}</Text>}
                      </Space>
                      <Text>{item.text}</Text>
                    </Space>
                  </List.Item>
                )}
              />
            </Card>
          )}
          <Paragraph style={{ whiteSpace: 'pre-wrap' }}>{skillDrawer?.content}</Paragraph>
        </Space>
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
          setSkillVersionDiff(null);
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
              width: 170,
              render: (_: any, record: any) => (
                <Space size="small">
                  <Button type="link" onClick={() => diffSkillVersion(record.id)}>
                    对比当前
                  </Button>
                  <Button type="link" onClick={() => restoreSkillVersion(record.id)}>
                    恢复
                  </Button>
                </Space>
              ),
            },
          ]}
          locale={{ emptyText: '暂无历史版本' }}
        />
      </Modal>

      <Modal
        title="Skill启用配置历史版本"
        open={skillConfigVersionOpen}
        width={860}
        footer={null}
        onCancel={() => {
          setSkillConfigVersionOpen(false);
          setSkillConfigVersions([]);
        }}
      >
        <Table
          loading={skillConfigVersionLoading}
          dataSource={skillConfigVersions}
          rowKey="id"
          pagination={{ pageSize: 6 }}
          columns={[
            { title: '版本ID', dataIndex: 'id', key: 'id', ellipsis: true },
            { title: '路径', dataIndex: 'path', key: 'path', ellipsis: true },
            { title: '归档时间', dataIndex: 'archivedAt', key: 'archivedAt', width: 190 },
            { title: '大小', dataIndex: 'sizeBytes', key: 'sizeBytes', width: 90 },
            {
              title: '操作',
              key: 'action',
              width: 110,
              render: (_: any, record: any) => (
                <Popconfirm
                  title="恢复这份启用配置？"
                  description="恢复前会先归档当前 enabled.yaml，并重新加载项目 Skill 状态。"
                  okText="恢复"
                  cancelText="取消"
                  onConfirm={() => restoreSkillConfigVersion(record.id)}
                >
                  <Button type="link">恢复</Button>
                </Popconfirm>
              ),
            },
          ]}
          locale={{ emptyText: '暂无启用配置历史版本' }}
        />
      </Modal>

      <Modal
        title={`${skillVersionDiff?.skillName || 'Skill'} 版本差异`}
        open={!!skillVersionDiff}
        width={980}
        footer={null}
        onCancel={() => setSkillVersionDiff(null)}
      >
        <Spin spinning={skillVersionDiffLoading}>
          {skillVersionDiff && (
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Descriptions bordered size="small" column={2}>
                <Descriptions.Item label="历史版本">{skillVersionDiff.versionId}</Descriptions.Item>
                <Descriptions.Item label="当前版本">{skillVersionDiff.right?.path || 'current'}</Descriptions.Item>
                <Descriptions.Item label="新增行">{skillVersionDiff.addedLines ?? 0}</Descriptions.Item>
                <Descriptions.Item label="删除行">{skillVersionDiff.removedLines ?? 0}</Descriptions.Item>
                <Descriptions.Item label="对比时间">{skillVersionDiff.comparedAt || '-'}</Descriptions.Item>
                <Descriptions.Item label="截断">
                  {skillVersionDiff.leftTruncated || skillVersionDiff.rightTruncated ? '是' : '否'}
                </Descriptions.Item>
              </Descriptions>
              <div style={{ maxHeight: 520, overflow: 'auto', border: '1px solid #f0f0f0', borderRadius: 6 }}>
                <List
                  size="small"
                  dataSource={(skillVersionDiff.diff || []).slice(0, 500)}
                  renderItem={(item: any) => {
                    const color = item.type === 'added' ? '#f6ffed' : item.type === 'removed' ? '#fff1f0' : '#fff';
                    const tagColor = item.type === 'added' ? 'success' : item.type === 'removed' ? 'error' : 'default';
                    return (
                      <List.Item style={{ background: color, fontFamily: 'monospace', padding: '4px 8px' }}>
                        <Space size="small" align="start" style={{ width: '100%' }}>
                          <Tag color={tagColor} style={{ minWidth: 78, textAlign: 'center' }}>{item.type}</Tag>
                          <Text type="secondary" style={{ minWidth: 90 }}>
                            {item.leftLine ?? '-'} / {item.rightLine ?? '-'}
                          </Text>
                          <Text style={{ whiteSpace: 'pre-wrap' }}>{item.text}</Text>
                        </Space>
                      </List.Item>
                    );
                  }}
                />
              </div>
              {(skillVersionDiff.diff || []).length > 500 && (
                <Text type="secondary">仅显示前 500 行差异，可通过产物页查看完整文件。</Text>
              )}
            </Space>
          )}
        </Spin>
      </Modal>

      <Modal
        title={`${skillQualityDetail?.skillName || 'Skill'} 语义质量详情`}
        open={!!skillQualityDetail}
        width={920}
        footer={null}
        onCancel={() => setSkillQualityDetail(null)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Descriptions bordered column={3} size="small">
            <Descriptions.Item label="质量状态">
              <Tag color={qualityColor(skillQualityDetail?.status)}>{skillQualityDetail?.status || 'unchecked'}</Tag>
            </Descriptions.Item>
            <Descriptions.Item label="质量分">{skillQualityDetail?.score ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="语义分">{skillQualityDetail?.semanticQuality?.score ?? '-'}</Descriptions.Item>
            <Descriptions.Item label="语义状态">
              <Tag color={qualityColor(skillQualityDetail?.semanticQuality?.status)}>
                {skillQualityDetail?.semanticQuality?.status || 'unknown'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="校验时间">{skillQualityDetail?.checkedAt || '-'}</Descriptions.Item>
            <Descriptions.Item label="报告路径">{skillQualityDetail?.reportPath || '-'}</Descriptions.Item>
          </Descriptions>

          <Progress
            percent={Number(skillQualityDetail?.semanticQuality?.score || 0)}
            status={skillQualityDetail?.semanticQuality?.status === 'failed' ? 'exception' : 'normal'}
          />

          <Table
            size="small"
            pagination={false}
            rowKey={(record: any) => record.id}
            dataSource={skillQualityDetail?.semanticQuality?.dimensions || []}
            columns={[
              { title: '维度', dataIndex: 'title', key: 'title' },
              {
                title: '状态',
                dataIndex: 'status',
                key: 'status',
                width: 120,
                render: (status: string) => <Tag color={qualityColor(status)}>{status}</Tag>,
              },
              { title: '分数', dataIndex: 'score', key: 'score', width: 90 },
              { title: '说明', dataIndex: 'message', key: 'message' },
              {
                title: '指标',
                dataIndex: 'metrics',
                key: 'metrics',
                render: (metrics: any) => (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    {metrics ? JSON.stringify(metrics) : '-'}
                  </Text>
                ),
              },
            ]}
            locale={{ emptyText: '暂无语义维度' }}
          />

          {(skillQualityDetail?.semanticQuality?.risks || []).length > 0 && (
            <Alert
              type="warning"
              showIcon
              message="语义风险"
              description={
                <List
                  size="small"
                  dataSource={skillQualityDetail.semanticQuality.risks}
                  renderItem={(risk: any) => (
                    <List.Item>
                      <Space size="small" wrap>
                        <Tag color={risk.severity === 'high' ? 'error' : 'warning'}>{risk.severity}</Tag>
                        <Text>{risk.message}</Text>
                      </Space>
                    </List.Item>
                  )}
                />
              }
            />
          )}

          {(skillQualityDetail?.semanticQuality?.recommendations || []).length > 0 && (
            <Alert
              type="info"
              showIcon
              message="改进建议"
              description={
                <List
                  size="small"
                  dataSource={skillQualityDetail.semanticQuality.recommendations}
                  renderItem={(item: string) => <List.Item>{item}</List.Item>}
                />
              }
            />
          )}
        </Space>
      </Modal>

      <Modal
        title={approvalDecision === 'approve' ? '批准工作流继续执行' : '驳回并取消工作流'}
        open={!!approvalTask}
        confirmLoading={approvalSubmitting}
        okText={approvalDecision === 'approve' ? '批准并恢复' : '确认驳回'}
        okButtonProps={{ danger: approvalDecision === 'reject' }}
        cancelText="取消"
        onOk={submitWorkflowApproval}
        onCancel={() => setApprovalTask(null)}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Alert
            type={approvalDecision === 'approve' ? 'info' : 'warning'}
            showIcon
            message={approvalTask?.result?.waiting_for_human?.prompt || '等待人工确认'}
            description={
              <Space direction="vertical" size={0}>
                <Text>任务：{approvalTask?.id}</Text>
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
              <TextArea rows={4} placeholder="记录本次批准或驳回原因" />
            </Form.Item>
          </Form>
        </Space>
      </Modal>
    </Spin>
  );
};

export default ProjectDetail;
