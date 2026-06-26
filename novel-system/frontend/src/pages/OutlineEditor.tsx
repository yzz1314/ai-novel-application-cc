import React, { useState, useEffect } from 'react';
import {
  Card,
  Tabs,
  Button,
  Modal,
  Form,
  Input,
  InputNumber,
  Select,
  Table,
  Space,
  Descriptions,
  Tag,
  Collapse,
  Popconfirm,
  message,
  Alert,
  Progress,
} from 'antd';
import { BookOutlined, UserOutlined, GlobalOutlined, FileTextOutlined, PlusOutlined, EditOutlined, CheckCircleOutlined, ToolOutlined, LockOutlined, UnlockOutlined, HistoryOutlined } from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import { bookApi, outlineApi, taskApi } from '../services/api';

const { TextArea } = Input;
const { Panel } = Collapse;

const ParagraphText: React.FC<{ content: string }> = ({ content }) => (
  <pre style={{
    whiteSpace: 'pre-wrap',
    wordBreak: 'break-word',
    margin: 0,
    fontFamily: 'inherit',
    maxHeight: 520,
    overflow: 'auto',
  }}>
    {content}
  </pre>
);

interface Character {
  name: string;
  role: string;
  description: string;
}

interface Volume {
  volumeNumber: number;
  volumeTitle: string;
  chapterCount: number;
  targetWordCount: number;
}

interface Chapter {
  chapterNumber: number;
  chapterTitle: string;
  plotGoal: string;
  targetWordCount: number;
}

const OutlineEditor: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const [outline, setOutline] = useState<any>(null);
  const [books, setBooks] = useState<any[]>([]);
  const [selectedBookId, setSelectedBookId] = useState('default');
  const [loading, setLoading] = useState(false);
  const [generateModalVisible, setGenerateModalVisible] = useState(false);
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [outlineJson, setOutlineJson] = useState('');
  const [projectSoulText, setProjectSoulText] = useState('');
  const [editNote, setEditNote] = useState('');
  const [outlineReviews, setOutlineReviews] = useState<any[]>([]);
  const [reviewing, setReviewing] = useState(false);
  const [soulVersions, setSoulVersions] = useState<any[]>([]);
  const [soulVersionOpen, setSoulVersionOpen] = useState(false);
  const [soulGovernanceLoading, setSoulGovernanceLoading] = useState(false);
  const [form] = Form.useForm();

  useEffect(() => {
    loadOutline();
  }, [projectId, selectedBookId]);

  const loadOutline = async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      const bookList = await bookApi.getList(projectId).catch(() => []);
      setBooks(bookList || []);
      const data = await outlineApi.get(projectId, selectedBookId || 'default');
      setOutline(data);
      const bookId = selectedBookId || data?.bookId || 'default';
      const reviews = await outlineApi.getReviews(projectId, bookId).catch(() => []);
      setOutlineReviews(reviews || []);
    } catch (error) {
      setOutline(null);
      setOutlineReviews([]);
    } finally {
      setLoading(false);
    }
  };

  const handleGenerate = async (values: any) => {
    if (!projectId) return;

    try {
      setLoading(true);
      await outlineApi.generate(projectId, {
        book_title: values.bookTitle,
        genre: values.genre,
        target_word_count: values.targetWordCount,
        target_volumes: values.targetVolumes,
        core_concept: values.coreConcept,
        world_view: values.worldView,
        main_conflict: values.mainConflict,
        use_project_skills: true,
      });

      message.success('大纲生成任务已启动，请稍后查看');
      setGenerateModalVisible(false);
      form.resetFields();

      // 轮询查看结果
      setSelectedBookId('default');
      setTimeout(() => loadOutline(), 5000);
    } catch (error) {
      message.error('生成失败');
    } finally {
      setLoading(false);
    }
  };

  const handleReviewOutline = async (autoFix = false) => {
    if (!projectId || !outline) return;
    try {
      setReviewing(true);
      const task: any = await outlineApi.review(projectId, {
        book_id: selectedBookId || outline.bookId || 'default',
        auto_fix: autoFix,
        autoFix,
        overwrite_boundary: autoFix,
        overwriteBoundary: autoFix,
      });
      message.success(autoFix ? '大纲补齐任务已启动' : '大纲审查任务已启动');

      for (let attempt = 0; attempt < 20; attempt += 1) {
        await new Promise((resolve) => setTimeout(resolve, 2000));
        const latest: any = await taskApi.getStatus(projectId, task.id);
        if (['SUCCESS', 'FAILED', 'PARTIAL', 'CANCELLED'].includes(latest.status)) {
          if (latest.status === 'SUCCESS' || latest.status === 'PARTIAL') {
            message.success(autoFix ? '大纲补齐完成' : '大纲审查完成');
          } else {
            message.error(autoFix ? '大纲补齐失败' : '大纲审查失败');
          }
          await loadOutline();
          return;
        }
      }
      message.info(autoFix ? '大纲补齐仍在运行，可稍后刷新查看报告' : '大纲审查仍在运行，可稍后刷新查看报告');
      await loadOutline();
    } catch (error) {
      message.error(autoFix ? '启动大纲补齐失败' : '启动大纲审查失败');
    } finally {
      setReviewing(false);
    }
  };

  const openEditModal = () => {
    if (!outline) return;
    const editableOutline = { ...outline };
    delete editableOutline.outlinePath;
    delete editableOutline.projectSoul;
    delete editableOutline.projectSoulPath;
    setOutlineJson(JSON.stringify(editableOutline, null, 2));
    setProjectSoulText(outline.projectSoul || '');
    setEditNote('');
    setEditModalVisible(true);
  };

  const handleSaveOutline = async () => {
    if (!projectId || !outline) return;

    let parsedOutline: any;
    try {
      parsedOutline = JSON.parse(outlineJson);
    } catch (error) {
      message.error('大纲 JSON 格式无效');
      return;
    }

    try {
      setLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      const result: any = await outlineApi.update(projectId, bookId, {
        outline: parsedOutline,
        projectSoul: projectSoulText,
        editNote,
        editor: 'human',
        createVersionSnapshot: true,
      });
      message.success(`大纲已保存：${result?.updatedPath || ''}`);
      setEditModalVisible(false);
      await loadOutline();
    } catch (error) {
      message.error('保存大纲失败');
    } finally {
      setLoading(false);
    }
  };

  const refreshProjectSoul = async () => {
    if (!projectId || !outline) return;
    const bookId = selectedBookId || outline.bookId || 'default';
    const soul = await outlineApi.getSoul(projectId, bookId);
    setOutline((current: any) => current ? {
      ...current,
      projectSoul: soul.content,
      projectSoulPath: soul.path,
      projectSoulGovernance: soul.governance,
    } : current);
  };

  const runSoulGovernance = async (action: 'lock' | 'unlock' | 'approve') => {
    if (!projectId || !outline) return;
    const bookId = selectedBookId || outline.bookId || 'default';
    try {
      setSoulGovernanceLoading(true);
      const payload = { actor: 'human', reviewer: 'human', note: `OutlineEditor ${action}`, lock: action === 'approve' };
      if (action === 'lock') {
        await outlineApi.lockSoul(projectId, bookId, payload);
        message.success('Project Soul已锁定');
      } else if (action === 'unlock') {
        await outlineApi.unlockSoul(projectId, bookId, payload);
        message.success('Project Soul已解锁');
      } else {
        await outlineApi.approveSoul(projectId, bookId, payload);
        message.success('Project Soul已批准并锁定');
      }
      await refreshProjectSoul();
    } catch (error) {
      message.error('更新Project Soul治理状态失败');
    } finally {
      setSoulGovernanceLoading(false);
    }
  };

  const openSoulVersions = async () => {
    if (!projectId || !outline) return;
    try {
      setSoulGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      const versions = await outlineApi.getSoulVersions(projectId, bookId);
      setSoulVersions(versions || []);
      setSoulVersionOpen(true);
    } catch (error) {
      message.error('加载Project Soul版本失败');
    } finally {
      setSoulGovernanceLoading(false);
    }
  };

  const soulGovernance = outline?.projectSoulGovernance || outline?.governance || {};

  const openChapterWorkspace = (volumeNumber?: number, chapter?: Partial<Chapter>) => {
    if (!projectId || !outline) return;
    const bookId = selectedBookId === 'default'
      ? (outline.bookId || books[0]?.bookId || 'default')
      : selectedBookId;
    const params = new URLSearchParams();
    params.set('bookId', bookId);
    if (volumeNumber) params.set('volume', String(volumeNumber));
    if (chapter?.chapterNumber) params.set('chapter', String(chapter.chapterNumber));
    if (chapter?.chapterTitle) params.set('title', chapter.chapterTitle);
    navigate(`/projects/${projectId}/chapters?${params.toString()}`);
  };

  const characterColumns = [
    {
      key: 'soul',
      label: (
        <span>
          <FileTextOutlined />
          Project Soul
        </span>
      ),
      children: outline?.projectSoul ? (
        <Card>
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Space wrap>
              <Tag color={soulGovernance.locked ? 'red' : 'green'}>
                {soulGovernance.locked ? '已锁定' : '可编辑'}
              </Tag>
              <Tag color={soulGovernance.approvalStatus === 'approved' ? 'success' : 'warning'}>
                {soulGovernance.approvalStatus || 'pending_review'}
              </Tag>
              {soulGovernance.updatedAt && <Tag>{soulGovernance.updatedAt}</Tag>}
            </Space>
            <Space wrap>
              {soulGovernance.locked ? (
                <Popconfirm
                  title="解锁Project Soul？"
                  description="解锁后可在大纲编辑弹窗中修改Project Soul。"
                  okText="解锁"
                  cancelText="取消"
                  onConfirm={() => runSoulGovernance('unlock')}
                >
                  <Button icon={<UnlockOutlined />} loading={soulGovernanceLoading}>
                    解锁
                  </Button>
                </Popconfirm>
              ) : (
                <Button icon={<LockOutlined />} loading={soulGovernanceLoading} onClick={() => runSoulGovernance('lock')}>
                  锁定
                </Button>
              )}
              <Button icon={<CheckCircleOutlined />} loading={soulGovernanceLoading} onClick={() => runSoulGovernance('approve')}>
                批准并锁定
              </Button>
              <Button icon={<HistoryOutlined />} loading={soulGovernanceLoading} onClick={openSoulVersions}>
                版本
              </Button>
            </Space>
          </Space>
          <div style={{ marginTop: 16 }}>
          <ParagraphText content={outline.projectSoul} />
          </div>
        </Card>
      ) : (
        <Card>
          <p>暂无Project Soul</p>
        </Card>
      ),
    },
    {
      title: '姓名',
      dataIndex: 'name',
      key: 'name',
    },
    {
      title: '角色类型',
      dataIndex: 'role',
      key: 'role',
      render: (role: string) => {
        const colorMap: Record<string, string> = {
          protagonist: 'red',
          supporting: 'blue',
          antagonist: 'orange',
        };
        return <Tag color={colorMap[role] || 'default'}>{role}</Tag>;
      },
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
    },
  ];

  const volumeColumns = [
    {
      title: '卷号',
      dataIndex: 'volumeNumber',
      key: 'volumeNumber',
    },
    {
      title: '卷名',
      dataIndex: 'volumeTitle',
      key: 'volumeTitle',
    },
    {
      title: '章节数',
      dataIndex: 'chapterCount',
      key: 'chapterCount',
    },
    {
      title: '目标字数',
      dataIndex: 'targetWordCount',
      key: 'targetWordCount',
      render: (count: number) => `${(count / 10000).toFixed(1)}万字`,
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: Volume) => (
        <Button type="link" onClick={() => openChapterWorkspace(record.volumeNumber)}>
          进入章节
        </Button>
      ),
    },
  ];

  const renderChapterList = (volumeNumber: number, chapters: Chapter[]) => (
    <Collapse>
      {chapters.map((chapter) => (
        <Panel
          key={chapter.chapterNumber}
          header={`第${chapter.chapterNumber}章 ${chapter.chapterTitle}`}
        >
          <Space direction="vertical" style={{ width: '100%' }}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label="剧情目标">{chapter.plotGoal}</Descriptions.Item>
              <Descriptions.Item label="目标字数">{chapter.targetWordCount}字</Descriptions.Item>
            </Descriptions>
            <Button type="primary" onClick={() => openChapterWorkspace(volumeNumber, chapter)}>
              创作/查看本章
            </Button>
          </Space>
        </Panel>
      ))}
    </Collapse>
  );

  const latestReview = outlineReviews[0];

  const reviewColumns = [
    { title: '报告', dataIndex: 'id', key: 'id', ellipsis: true },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (status: string) => (
        <Tag color={status === 'passed' ? 'success' : 'warning'}>{status || 'unknown'}</Tag>
      ),
    },
    { title: '评分', dataIndex: 'score', key: 'score', width: 90 },
    { title: '错误', dataIndex: 'errorCount', key: 'errorCount', width: 80 },
    { title: '警告', dataIndex: 'warningCount', key: 'warningCount', width: 80 },
    { title: '时间', dataIndex: 'reviewedAt', key: 'reviewedAt', width: 190 },
  ];

  const tabItems = [
    {
      key: 'overview',
      label: (
        <span>
          <BookOutlined />
          概览
        </span>
      ),
      children: outline ? (
        <Card>
          <Descriptions column={2} bordered>
            <Descriptions.Item label="书名">{outline.bookTitle}</Descriptions.Item>
            <Descriptions.Item label="类型">{outline.genre}</Descriptions.Item>
            <Descriptions.Item label="目标字数" span={2}>
              {(outline.targetWordCount / 10000).toFixed(0)}万字
            </Descriptions.Item>
            <Descriptions.Item label="核心概念" span={2}>
              {outline.coreConcept}
            </Descriptions.Item>
            <Descriptions.Item label="世界观" span={2}>
              {outline.worldView}
            </Descriptions.Item>
            <Descriptions.Item label="主要冲突" span={2}>
              {outline.mainConflict}
            </Descriptions.Item>
          </Descriptions>
        </Card>
      ) : (
        <Card>
          <p>暂无大纲，请先生成</p>
        </Card>
      ),
    },
    {
      key: 'characters',
      label: (
        <span>
          <UserOutlined />
          人物
        </span>
      ),
      children: outline ? (
        <Table
          columns={characterColumns}
          dataSource={outline.characters}
          rowKey="name"
          pagination={false}
        />
      ) : (
        <Card>
          <p>暂无人物信息</p>
        </Card>
      ),
    },
    {
      key: 'worldSettings',
      label: (
        <span>
          <GlobalOutlined />
          世界观
        </span>
      ),
      children: outline && outline.worldSettings ? (
        <Card>
          <Collapse>
            {outline.worldSettings.map((setting: any, index: number) => (
              <Panel key={index} header={setting.name}>
                <p><strong>类型：</strong>{setting.category}</p>
                <p><strong>描述：</strong>{setting.description}</p>
              </Panel>
            ))}
          </Collapse>
        </Card>
      ) : (
        <Card>
          <p>暂无世界观设定</p>
        </Card>
      ),
    },
    {
      key: 'volumes',
      label: (
        <span>
          <FileTextOutlined />
          分卷章节
        </span>
      ),
      children: outline && outline.volumes ? (
        <div>
          <Table
            columns={volumeColumns}
            dataSource={outline.volumes}
            rowKey="volumeNumber"
            pagination={false}
            expandable={{
              expandedRowRender: (record: any) => renderChapterList(record.volumeNumber, record.chapters || []),
            }}
          />
        </div>
      ) : (
        <Card>
          <p>暂无分卷章节信息</p>
        </Card>
      ),
    },
    {
      key: 'reviews',
      label: (
        <span>
          <CheckCircleOutlined />
          审查报告
        </span>
      ),
      children: (
        <Space direction="vertical" style={{ width: '100%' }} size="middle">
          {latestReview ? (
            <Card>
              <Space direction="vertical" style={{ width: '100%' }}>
                <Alert
                  type={latestReview.status === 'passed' ? 'success' : 'warning'}
                  showIcon
                  message={latestReview.summary || '大纲审查报告'}
                />
                <Progress percent={latestReview.score || 0} status={latestReview.status === 'passed' ? 'success' : 'active'} />
                <Descriptions column={4} size="small" bordered>
                  <Descriptions.Item label="章节数">{latestReview.metrics?.totalChapters || 0}</Descriptions.Item>
                  <Descriptions.Item label="边界完整">{latestReview.metrics?.chaptersWithBoundary || 0}</Descriptions.Item>
                  <Descriptions.Item label="错误">{latestReview.errorCount || 0}</Descriptions.Item>
                  <Descriptions.Item label="警告">{latestReview.warningCount || 0}</Descriptions.Item>
                  <Descriptions.Item label="自动补齐">
                    {latestReview.autoFix?.applied
                      ? `${latestReview.autoFix?.completion?.changedChapterCount || 0}章`
                      : '未执行'}
                  </Descriptions.Item>
                </Descriptions>
                <Collapse>
                  {(latestReview.findings || []).slice(0, 12).map((finding: any, index: number) => (
                    <Panel
                      key={`${finding.code}-${index}`}
                      header={`${finding.severity} · ${finding.code} · ${finding.path || ''}`}
                    >
                      <p>{finding.message}</p>
                    </Panel>
                  ))}
                </Collapse>
              </Space>
            </Card>
          ) : (
            <Card>
              <p>暂无大纲审查报告</p>
            </Card>
          )}
          <Table
            columns={reviewColumns}
            dataSource={outlineReviews}
            rowKey="id"
            pagination={{ pageSize: 5 }}
          />
        </Space>
      ),
    },
  ];

  return (
    <div>
      <Card
        title="大纲编辑器"
        extra={
          <Space>
            {books.length > 0 && (
              <Select
                value={selectedBookId}
                style={{ width: 220 }}
                onChange={setSelectedBookId}
                options={[
                  { label: '最新大纲', value: 'default' },
                  ...books.map((book) => ({ label: book.bookTitle || book.bookId, value: book.bookId })),
                ]}
              />
            )}
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setGenerateModalVisible(true)}
            >
              生成新大纲
            </Button>
            <Button
              icon={<EditOutlined />}
              disabled={!outline}
              onClick={openEditModal}
            >
              编辑大纲
            </Button>
            <Button
              icon={<CheckCircleOutlined />}
              disabled={!outline}
              loading={reviewing}
              onClick={() => handleReviewOutline(false)}
            >
              审查大纲
            </Button>
            <Button
              icon={<ToolOutlined />}
              disabled={!outline}
              loading={reviewing}
              onClick={() => handleReviewOutline(true)}
            >
              补齐边界
            </Button>
          </Space>
        }
      >
        <Tabs defaultActiveKey="overview" items={tabItems} />
      </Card>

      <Modal
        title="生成大纲"
        open={generateModalVisible}
        onOk={() => form.submit()}
        onCancel={() => {
          setGenerateModalVisible(false);
          form.resetFields();
        }}
        confirmLoading={loading}
        width={700}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleGenerate}
        >
          <Form.Item
            name="bookTitle"
            label="书名"
            rules={[{ required: true, message: '请输入书名' }]}
          >
            <Input placeholder="例如：修真传奇" />
          </Form.Item>

          <Form.Item
            name="genre"
            label="类型"
            rules={[{ required: true, message: '请输入类型' }]}
          >
            <Input placeholder="例如：玄幻修真" />
          </Form.Item>

          <Form.Item
            name="targetWordCount"
            label="目标字数"
            rules={[{ required: true, message: '请输入目标字数' }]}
          >
            <InputNumber
              min={100000}
              max={10000000}
              step={100000}
              style={{ width: '100%' }}
              placeholder="例如：1000000（100万字）"
            />
          </Form.Item>

          <Form.Item
            name="targetVolumes"
            label="目标卷数"
          >
            <InputNumber
              min={1}
              max={20}
              style={{ width: '100%' }}
              placeholder="留空自动计算"
            />
          </Form.Item>

          <Form.Item
            name="coreConcept"
            label="核心概念"
            rules={[{ required: true, message: '请输入核心概念' }]}
          >
            <TextArea
              rows={3}
              placeholder="例如：废材逆袭，修炼成仙"
            />
          </Form.Item>

          <Form.Item
            name="worldView"
            label="世界观"
          >
            <TextArea
              rows={3}
              placeholder="例如：修真世界，分为凡间、灵界、仙界三大境界"
            />
          </Form.Item>

          <Form.Item
            name="mainConflict"
            label="主要冲突"
          >
            <TextArea
              rows={3}
              placeholder="例如：主角与各大宗门的争斗"
            />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑大纲"
        open={editModalVisible}
        onOk={handleSaveOutline}
        onCancel={() => setEditModalVisible(false)}
        confirmLoading={loading}
        width={920}
        okText="保存"
        cancelText="取消"
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <TextArea
            value={projectSoulText}
            onChange={(event) => setProjectSoulText(event.target.value)}
            rows={7}
            disabled={!!soulGovernance.locked}
            placeholder={soulGovernance.locked ? 'Project Soul已锁定，请先解锁' : 'Project Soul'}
          />
          <TextArea
            value={editNote}
            onChange={(event) => setEditNote(event.target.value)}
            rows={2}
            placeholder="编辑说明"
          />
          <TextArea
            value={outlineJson}
            onChange={(event) => setOutlineJson(event.target.value)}
            rows={24}
            style={{ fontFamily: 'monospace' }}
            placeholder="完整大纲 JSON"
          />
        </Space>
      </Modal>

      <Modal
        title="Project Soul历史版本"
        open={soulVersionOpen}
        footer={null}
        width={760}
        onCancel={() => setSoulVersionOpen(false)}
      >
        <Table
          dataSource={soulVersions}
          rowKey="id"
          pagination={{ pageSize: 6 }}
          columns={[
            { title: '版本ID', dataIndex: 'id', key: 'id', ellipsis: true },
            { title: '路径', dataIndex: 'path', key: 'path', ellipsis: true },
            { title: '归档时间', dataIndex: 'archivedAt', key: 'archivedAt', width: 190 },
            { title: '大小', dataIndex: 'sizeBytes', key: 'sizeBytes', width: 100 },
          ]}
          locale={{ emptyText: '暂无Project Soul历史版本' }}
        />
      </Modal>
    </div>
  );
};

export default OutlineEditor;
