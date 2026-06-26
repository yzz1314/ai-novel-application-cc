import React, { useEffect, useState } from 'react';
import { Card, Table, Button, Space, Tag, Modal, Form, Input, message } from 'antd';
import { PlusOutlined, EyeOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { projectApi } from '../services/api';
import type { ColumnsType } from 'antd/es/table';

interface Project {
  id: string;
  projectName: string;
  description: string;
  genre: string;
  status: string;
  createdAt: string;
  sampleCount: number;
}

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
    genre: project.genre || project.sampleGroupType || project.sample_group_type || '-',
    status: (project.status || '').toLowerCase(),
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
      title: '类型',
      dataIndex: 'genre',
      key: 'genre',
      render: (genre) => <Tag color="blue">{genre}</Tag>,
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
      render: (status) => {
        const colorMap: Record<string, string> = {
          'active': 'green',
          'completed': 'blue',
          'archived': 'default'
        };
        return <Tag color={colorMap[status] || 'default'}>{status}</Tag>;
      },
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
        sampleGroupType: 'SAME_GENRE',
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
      content: '删除后数据将无法恢复，是否继续？',
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
            label="小说类型"
            rules={[{ required: true, message: '请输入小说类型' }]}
          >
            <Input placeholder="例如：玄幻、都市、历史" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ProjectList;
