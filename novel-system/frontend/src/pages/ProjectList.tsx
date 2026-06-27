import React, { useEffect, useMemo, useState } from 'react';
import { Button, Card, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { EyeOutlined, InboxOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { projectApi } from '../services/api';
import type { ColumnsType } from 'antd/es/table';

const { Search } = Input;
const { Text } = Typography;

interface Project {
  id: string;
  projectName: string;
  description: string;
  genre: string;
  sampleGroupType: string;
  status: string;
  createdAt: string;
  sampleCount: number;
}

const sampleGroupLabels: Record<string, string> = {
  SAME_AUTHOR: '同作者样本',
  SAME_GENRE: '同类型样本',
  MIXED: '混合样本',
};

const statusLabels: Record<string, string> = {
  CREATED: '已创建',
  INGESTING: '导入中',
  CHUNKED: '已分块',
  ANALYZING: '分析中',
  ANALYZED: '已分析',
  OUTLINING: '大纲中',
  WRITING: '创作中',
  COMPLETED: '已完成',
  FAILED: '失败',
  ARCHIVED: '已归档',
};

const statusColors: Record<string, string> = {
  CREATED: 'default',
  INGESTING: 'processing',
  CHUNKED: 'cyan',
  ANALYZING: 'processing',
  ANALYZED: 'blue',
  OUTLINING: 'purple',
  WRITING: 'green',
  COMPLETED: 'success',
  FAILED: 'error',
  ARCHIVED: 'default',
};

const projectStatusGuide: Record<string, string> = {
  CREATED: '上传样本并启动导入',
  INGESTING: '等待样本导入完成',
  CHUNKED: '进入样本分析',
  ANALYZING: '等待分析任务完成',
  ANALYZED: '生成 Skill 或大纲',
  OUTLINING: '审查并确认大纲',
  WRITING: '继续章节创作',
  COMPLETED: '查看终稿和产物',
  FAILED: '查看任务失败原因',
  ARCHIVED: '已归档，仅保留记录',
};

const statusOptions = Object.entries(statusLabels).map(([value, label]) => ({ value, label }));
const sampleGroupOptions = Object.entries(sampleGroupLabels).map(([value, label]) => ({ value, label }));

const compareText = (left?: string, right?: string) => String(left || '').localeCompare(String(right || ''));
const timestampValue = (value?: string) => value ? new Date(value).getTime() || 0 : 0;

const ProjectList: React.FC = () => {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [query, setQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [sampleGroupFilter, setSampleGroupFilter] = useState<string | undefined>();
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);
  const [bulkArchiving, setBulkArchiving] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    loadProjects();
  }, []);

  useEffect(() => {
    if (searchParams.get('action') !== 'create') return;
    setModalVisible(true);
    const next = new URLSearchParams(searchParams);
    next.delete('action');
    setSearchParams(next, { replace: true });
  }, [searchParams, setSearchParams]);

  const normalizeProject = (project: any): Project => ({
    id: project.id,
    projectName: project.projectName || project.name || project.title || project.id,
    description: project.description || '',
    genre: project.genre || '',
    sampleGroupType: project.sampleGroupType || project.sample_group_type || 'SAME_GENRE',
    status: (project.status || 'CREATED').toUpperCase(),
    createdAt: project.createdAt || project.created_at || '',
    sampleCount: project.sampleCount || 0,
  });

  const loadProjects = async () => {
    try {
      setLoading(true);
      const data = await projectApi.getList();
      setProjects((data || []).map(normalizeProject));
    } catch (error) {
      message.error('加载项目列表失败');
    } finally {
      setLoading(false);
    }
  };

  const filteredProjects = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase();
    return projects.filter((project) => {
      const matchesQuery = !normalizedQuery || [
        project.projectName,
        project.description,
        project.genre,
        project.id,
      ].some((value) => String(value || '').toLowerCase().includes(normalizedQuery));
      const matchesStatus = !statusFilter || project.status === statusFilter;
      const matchesSampleGroup = !sampleGroupFilter || project.sampleGroupType === sampleGroupFilter;
      return matchesQuery && matchesStatus && matchesSampleGroup;
    });
  }, [projects, query, statusFilter, sampleGroupFilter]);

  const selectedProjects = useMemo(
    () => projects.filter((project) => selectedRowKeys.includes(project.id)),
    [projects, selectedRowKeys]
  );

  const activeSelectedCount = selectedProjects.filter((project) => project.status !== 'ARCHIVED').length;

  const clearFilters = () => {
    setQuery('');
    setStatusFilter(undefined);
    setSampleGroupFilter(undefined);
  };

  const archiveProjects = async (targetProjects: Project[]) => {
    if (!targetProjects.length) return;
    setBulkArchiving(true);
    try {
      await Promise.all(targetProjects.map((project) => projectApi.delete(project.id)));
      message.success(`已归档 ${targetProjects.length} 个项目`);
      setSelectedRowKeys([]);
      await loadProjects();
    } catch (error) {
      message.error('项目归档失败');
    } finally {
      setBulkArchiving(false);
    }
  };

  const confirmArchiveProjects = (targetProjects: Project[]) => {
    const activeProjects = targetProjects.filter((project) => project.status !== 'ARCHIVED');
    if (!activeProjects.length) {
      message.info('所选项目已全部归档');
      return;
    }
    Modal.confirm({
      title: activeProjects.length > 1 ? '确认批量归档' : '确认归档',
      content: `将归档 ${activeProjects.length} 个项目；归档后仍可在列表中筛选查看。`,
      okText: '归档',
      cancelText: '取消',
      onOk: () => archiveProjects(activeProjects),
    });
  };

  const columns: ColumnsType<Project> = [
    {
      title: '项目名称',
      dataIndex: 'projectName',
      key: 'projectName',
      sorter: (a, b) => compareText(a.projectName, b.projectName),
      render: (text, record) => (
        <a onClick={() => navigate(`/projects/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
    {
      title: '题材',
      dataIndex: 'genre',
      key: 'genre',
      sorter: (a, b) => compareText(a.genre, b.genre),
      render: (genre) => genre ? <Tag color="geekblue">{genre}</Tag> : '-',
    },
    {
      title: '样本分组',
      dataIndex: 'sampleGroupType',
      key: 'sampleGroupType',
      sorter: (a, b) => compareText(a.sampleGroupType, b.sampleGroupType),
      render: (sampleGroupType) => (
        <Tag color="blue">{sampleGroupLabels[sampleGroupType] || sampleGroupType}</Tag>
      ),
    },
    {
      title: '样本数',
      dataIndex: 'sampleCount',
      key: 'sampleCount',
      sorter: (a, b) => (a.sampleCount || 0) - (b.sampleCount || 0),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      sorter: (a, b) => compareText(a.status, b.status),
      render: (status) => (
        <Tag color={statusColors[status] || 'default'}>{statusLabels[status] || status}</Tag>
      ),
    },
    {
      title: '下一步',
      key: 'nextStep',
      render: (_, record) => <Text type="secondary">{projectStatusGuide[record.status] || '-'}</Text>,
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      defaultSortOrder: 'descend',
      sorter: (a, b) => timestampValue(a.createdAt) - timestampValue(b.createdAt),
    },
    {
      title: '操作',
      key: 'action',
      render: (_, record) => (
        <Space size="small">
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => navigate(`/projects/${record.id}`)}
          >
            查看
          </Button>
          <Button
            type="link"
            danger
            icon={<InboxOutlined />}
            disabled={record.status === 'ARCHIVED'}
            onClick={() => confirmArchiveProjects([record])}
          >
            归档
          </Button>
        </Space>
      ),
    },
  ];

  const handleCreate = async (values: any) => {
    try {
      setLoading(true);
      await projectApi.create({
        name: values.projectName,
        description: values.description,
        genre: values.genre,
        sampleGroupType: values.sampleGroupType,
      });
      message.success('项目创建成功');
      setModalVisible(false);
      form.resetFields();
      await loadProjects();
    } catch (error) {
      message.error('项目创建失败');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div>
      <Card
        title="项目列表"
        extra={
          <Space wrap>
            <Button icon={<ReloadOutlined />} onClick={() => loadProjects()} loading={loading}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setModalVisible(true)}
            >
              创建项目
            </Button>
          </Space>
        }
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Space wrap>
            <Search
              allowClear
              placeholder="搜索项目名称、题材、描述或 ID"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              style={{ width: 280 }}
            />
            <Select
              allowClear
              placeholder="状态"
              value={statusFilter}
              onChange={setStatusFilter}
              options={statusOptions}
              style={{ width: 150 }}
            />
            <Select
              allowClear
              placeholder="样本分组"
              value={sampleGroupFilter}
              onChange={setSampleGroupFilter}
              options={sampleGroupOptions}
              style={{ width: 170 }}
            />
            <Button onClick={clearFilters}>清空筛选</Button>
            <Button
              danger
              icon={<InboxOutlined />}
              disabled={!activeSelectedCount}
              loading={bulkArchiving}
              onClick={() => confirmArchiveProjects(selectedProjects)}
            >
              批量归档
            </Button>
            <Text type="secondary">
              显示 {filteredProjects.length} / {projects.length}，已选 {selectedRowKeys.length}
            </Text>
          </Space>

          <Table
            columns={columns}
            dataSource={filteredProjects}
            loading={loading}
            rowKey="id"
            rowSelection={{
              selectedRowKeys,
              onChange: setSelectedRowKeys,
              getCheckboxProps: (record) => ({ disabled: record.status === 'ARCHIVED' }),
            }}
          />
        </Space>
      </Card>

      <Modal
        title="创建新项目"
        open={modalVisible}
        onOk={() => form.submit()}
        onCancel={() => {
          setModalVisible(false);
          form.resetFields();
        }}
        confirmLoading={loading}
        okText="创建"
        cancelText="取消"
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleCreate}
        >
          <Form.Item
            name="projectName"
            label="项目名称"
            rules={[{ required: true, message: '请输入项目名称' }]}
          >
            <Input placeholder="例如：玄幻修真小说创作" />
          </Form.Item>
          <Form.Item
            name="description"
            label="项目描述"
          >
            <Input.TextArea
              rows={3}
              placeholder="简要描述你的创作计划"
            />
          </Form.Item>
          <Form.Item
            name="genre"
            label="题材"
            rules={[{ max: 120, message: 'genre must not exceed 120 characters' }]}
          >
            <Input placeholder="玄幻修真 / 都市异能 / 悬疑探案" />
          </Form.Item>
          <Form.Item
            name="sampleGroupType"
            label="样本分组类型"
            initialValue="SAME_GENRE"
            rules={[{ required: true, message: '请选择样本分组类型' }]}
          >
            <Select options={sampleGroupOptions} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProjectList;
