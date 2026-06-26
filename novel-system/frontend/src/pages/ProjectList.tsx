import React, { useEffect, useState } from 'react';
import { Card, Table, Button, Space, Tag, Modal, Form, Input, Select, message } from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { projectApi } from '../services/api';
import type { ColumnsType } from 'antd/es/table';

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

const ProjectList: React.FC = () => {
  const navigate = useNavigate();
  const [projects, setProjects] = useState<Project[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalVisible, setModalVisible] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    loadProjects();
  }, []);

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

  const columns: ColumnsType<Project> = [
    {
      title: '项目名称',
      dataIndex: 'projectName',
      key: 'projectName',
      render: (text, record) => (
        <a onClick={() => navigate(`/projects/${record.id}`)}>{text}</a>
      ),
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
    },
    {
      title: '题材',
      dataIndex: 'genre',
      key: 'genre',
      render: (genre) => genre ? <Tag color="geekblue">{genre}</Tag> : '-',
    },
    {
      title: '样本分组',
      dataIndex: 'sampleGroupType',
      key: 'sampleGroupType',
      render: (sampleGroupType) => (
        <Tag color="blue">{sampleGroupLabels[sampleGroupType] || sampleGroupType}</Tag>
      ),
    },
    {
      title: '样本数量',
      dataIndex: 'sampleCount',
      key: 'sampleCount',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (status) => (
        <Tag color={statusColors[status] || 'default'}>{statusLabels[status] || status}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
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
            icon={<DeleteOutlined />}
            onClick={() => handleDelete(record.id)}
          >
            删除
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

  const handleDelete = async (projectId: string) => {
    Modal.confirm({
      title: '确认删除',
      content: '删除后项目会归档，是否继续？',
      okText: '确认',
      cancelText: '取消',
      onOk: async () => {
        try {
          await projectApi.delete(projectId);
          message.success('删除成功');
          await loadProjects();
        } catch (error) {
          message.error('删除失败');
        }
      },
    });
  };

  return (
    <div>
      <Card
        title="项目列表"
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setModalVisible(true)}
          >
            创建项目
          </Button>
        }
      >
        <Table
          columns={columns}
          dataSource={projects}
          loading={loading}
          rowKey="id"
        />
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
            <Select
              options={[
                { value: 'SAME_GENRE', label: sampleGroupLabels.SAME_GENRE },
                { value: 'SAME_AUTHOR', label: sampleGroupLabels.SAME_AUTHOR },
                { value: 'MIXED', label: sampleGroupLabels.MIXED },
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProjectList;
