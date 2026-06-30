import React, { useState, useEffect } from 'react';
import {
  Card,
  Table,
  Button,
  Space,
  Modal,
  Form,
  InputNumber,
  Switch,
  Tag,
  Drawer,
  Input,
  Select,
  Descriptions,
  Progress,
  message,
  Empty,
  Alert,
  Popconfirm,
  List,
  Row,
  Col,
} from 'antd';
import {
  EditOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  PlusOutlined,
  EyeOutlined,
  SendOutlined,
  HistoryOutlined,
  AuditOutlined,
  SaveOutlined,
  CloseOutlined,
  DiffOutlined,
} from '@ant-design/icons';
import { useParams, useSearchParams } from 'react-router-dom';
import { bookApi, chapterApi, retrievalApi } from '../services/api';

const { TextArea } = Input;

const readAny = (record: any, ...keys: string[]) => {
  if (!record) return undefined;
  for (const key of keys) {
    if (record[key] !== undefined && record[key] !== null) {
      return record[key];
    }
  }
  return undefined;
};

const getRevisionQuality = (record: any) => readAny(record, 'revisionQuality', 'revision_quality') || {};

const revisionQualityColor = (status?: string) => {
  if (status === 'passed') return 'green';
  if (status === 'needs_revision') return 'orange';
  if (status === 'unavailable') return 'gold';
  if (status === 'skipped') return 'default';
  return 'blue';
};

const renderRevisionQualityTag = (quality: any) => {
  if (!quality || Object.keys(quality).length === 0) return <Tag>未复核</Tag>;
  const status = readAny(quality, 'status') || 'unknown';
  const score = readAny(quality, 'score');
  return <Tag color={revisionQualityColor(status)}>{status}{score !== undefined && score !== null ? ` ${score}` : ''}</Tag>;
};

interface Chapter {
  id: string;
  volumeNumber: number;
  chapterNumber: number;
  chapterTitle: string;
  wordCount: number;
  status: string;
  stage?: string;
  isFinal?: boolean;
  qualityScore?: number;
  reviewStatus?: string;
  humanReviewStatus?: string;
  boundaryCheck?: any;
  revisionQuality?: any;
  revisionHistory?: any[];
  humanReviewHistory?: any[];
  createdAt: string;
}

const ChapterWriter: React.FC = () => {
  const { projectId } = useParams<{ projectId: string }>();
  const [searchParams, setSearchParams] = useSearchParams();
  const [books, setBooks] = useState<any[]>([]);
  const [selectedBookId, setSelectedBookId] = useState('default');
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [loading, setLoading] = useState(false);
  const [generateModalVisible, setGenerateModalVisible] = useState(false);
  const [viewDrawerVisible, setViewDrawerVisible] = useState(false);
  const [currentChapter, setCurrentChapter] = useState<any>(null);
  const [contextPacks, setContextPacks] = useState<any[]>([]);
  const [currentContextPack, setCurrentContextPack] = useState<any>(null);
  const [revisionModalVisible, setRevisionModalVisible] = useState(false);
  const [revisionTarget, setRevisionTarget] = useState<Chapter | null>(null);
  const [reviewModalVisible, setReviewModalVisible] = useState(false);
  const [reviewTarget, setReviewTarget] = useState<Chapter | null>(null);
  const [editingChapter, setEditingChapter] = useState(false);
  const [editTitle, setEditTitle] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editNote, setEditNote] = useState('');
  const [chapterVersions, setChapterVersions] = useState<any[]>([]);
  const [chapterReviews, setChapterReviews] = useState<any[]>([]);
  const [diffModalVisible, setDiffModalVisible] = useState(false);
  const [chapterDiff, setChapterDiff] = useState<any>(null);
  const [diffLoading, setDiffLoading] = useState(false);
  const [selectedChapterKeys, setSelectedChapterKeys] = useState<React.Key[]>([]);
  const [selectedChapters, setSelectedChapters] = useState<Chapter[]>([]);
  const [form] = Form.useForm();
  const [revisionForm] = Form.useForm();
  const [reviewForm] = Form.useForm();
  const searchKey = searchParams.toString();

  useEffect(() => {
    const requestedBookId = searchParams.get('bookId');
    if (requestedBookId && requestedBookId !== selectedBookId) {
      setSelectedBookId(requestedBookId);
      return;
    }
    loadChapters();
  }, [projectId, selectedBookId, searchKey]);

  const clearChapterDeepLink = () => {
    setSearchParams((params) => {
      const next = new URLSearchParams(params);
      next.delete('volume');
      next.delete('chapter');
      next.delete('title');
      return next;
    }, { replace: true });
  };

  const handleChapterDeepLink = async (
    loadedChapters: Chapter[],
    bookId: string,
    loadedContextPacks: any[]
  ) => {
    const requestedVolume = Number(searchParams.get('volume'));
    const requestedChapter = Number(searchParams.get('chapter'));
    if (!projectId || !requestedVolume || !requestedChapter) return;

    const target = loadedChapters.find((chapter) =>
      Number(chapter.volumeNumber) === requestedVolume &&
      Number(chapter.chapterNumber) === requestedChapter
    );
    if (target) {
      await handleView(target, bookId, loadedContextPacks);
      clearChapterDeepLink();
      return;
    }

    form.setFieldsValue({
      volumeNumber: requestedVolume,
      chapterNumber: requestedChapter,
      chapterTitle: searchParams.get('title') || '',
    });
    setGenerateModalVisible(true);
    clearChapterDeepLink();
  };

  const loadChapters = async () => {
    if (!projectId) return;
    try {
      setLoading(true);
      const bookList = await bookApi.getList(projectId).catch(() => []);
      setBooks(bookList || []);
      const requestedBookId = searchParams.get('bookId');
      const effectiveBookId = (requestedBookId && requestedBookId !== 'default')
        ? requestedBookId
        : selectedBookId === 'default'
        ? (bookList?.[0]?.bookId || 'default')
        : selectedBookId;
      if (requestedBookId && requestedBookId !== selectedBookId) {
        setSelectedBookId(requestedBookId);
      }
      const data = await chapterApi.getList(projectId, effectiveBookId);
      const packs = await retrievalApi.getContextPacks(projectId).catch(() => []);
      setChapters(data || []);
      setSelectedChapterKeys([]);
      setSelectedChapters([]);
      setContextPacks(packs || []);
      await handleChapterDeepLink(data || [], effectiveBookId, packs || []);
    } catch (error) {
      setChapters([]);
    } finally {
      setLoading(false);
    }
  };

  const handleGenerate = async (values: any) => {
    if (!projectId) return;

    try {
      setLoading(true);
      const effectiveBookId = selectedBookId === 'default'
        ? (books[0]?.bookId || 'default')
        : selectedBookId;
      await chapterApi.generate(projectId, {
        book_id: effectiveBookId,
        volume_number: values.volumeNumber,
        chapter_number: values.chapterNumber,
        chapter_title: values.chapterTitle,
        target_word_count: values.targetWordCount || 3000,
        use_project_skills: values.useProjectSkills,
        use_previous_context: values.usePreviousContext,
        auto_review: values.autoReview,
      });

      message.success('章节生成任务已启动，请稍后查看');
      setGenerateModalVisible(false);
      form.resetFields();

      setTimeout(() => loadChapters(), 5000);
    } catch (error) {
      message.error('生成失败');
    } finally {
      setLoading(false);
    }
  };

  const handleView = async (chapter: Chapter, bookIdOverride?: string, contextPacksOverride?: any[]) => {
    if (!projectId) return;

    try {
      setLoading(true);
      const bookId = bookIdOverride || (selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId);
      const data = await chapterApi.getContent(
        projectId,
        bookId,
        chapter.volumeNumber,
        chapter.chapterNumber
      );
      setCurrentChapter(data);
      setEditingChapter(false);
      setEditTitle(data?.chapterTitle || '');
      setEditContent(data?.content || '');
      setEditNote('');
      const versions = await chapterApi
        .getVersions(projectId, bookId, chapter.volumeNumber, chapter.chapterNumber)
        .catch(() => []);
      const reviews = await chapterApi
        .getReviews(projectId, bookId, chapter.volumeNumber, chapter.chapterNumber)
        .catch(() => []);
      setChapterVersions(versions || []);
      setChapterReviews(reviews || []);
      const packs = contextPacksOverride || contextPacks;
      const pack = packs.find((item) =>
        item.bookId === bookId &&
        Number(item.volumeNumber) === Number(chapter.volumeNumber) &&
        Number(item.chapterNumber) === Number(chapter.chapterNumber)
      );
      if (pack?.id) {
        const packDetail = await retrievalApi.getContextPack(projectId, pack.id).catch(() => null);
        setCurrentContextPack(packDetail);
      } else {
        setCurrentContextPack(null);
      }
      setViewDrawerVisible(true);
    } catch (error) {
      message.error('加载章节内容失败');
    } finally {
      setLoading(false);
    }
  };

  const refreshCurrentChapterArtifacts = async (
    bookId: string,
    volumeNumber: number,
    chapterNumber: number
  ) => {
    if (!projectId) return;
    const chapterDetail = await chapterApi.getContent(projectId, bookId, volumeNumber, chapterNumber);
    const versions = await chapterApi.getVersions(projectId, bookId, volumeNumber, chapterNumber).catch(() => []);
    const reviews = await chapterApi.getReviews(projectId, bookId, volumeNumber, chapterNumber).catch(() => []);
    setCurrentChapter(chapterDetail);
    setChapterVersions(versions || []);
    setChapterReviews(reviews || []);
    setEditTitle(chapterDetail?.chapterTitle || '');
    setEditContent(chapterDetail?.content || '');
    setEditNote('');
  };

  const openRevisionModal = (chapter: Chapter) => {
    setRevisionTarget(chapter);
    revisionForm.setFieldsValue({
      userInstruction: '',
      maxIterations: 1,
      includeBoundaryWarnings: true,
      createVersionSnapshot: true,
      sourceStage: chapter.isFinal ? 'final' : 'draft',
    });
    setRevisionModalVisible(true);
  };

  const handleRevision = async (values: any) => {
    if (!projectId || !revisionTarget) return;

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const task: any = await chapterApi.revise(
        projectId,
        bookId,
        revisionTarget.volumeNumber,
        revisionTarget.chapterNumber,
        {
          userInstruction: values.userInstruction,
          maxIterations: values.maxIterations,
          includeBoundaryWarnings: values.includeBoundaryWarnings,
          createVersionSnapshot: values.createVersionSnapshot,
          sourceStage: values.sourceStage,
        }
      );
      message.success(`返修任务已启动：${task?.id || ''}`);
      setRevisionModalVisible(false);
      setRevisionTarget(null);
      revisionForm.resetFields();
      setTimeout(() => loadChapters(), 5000);
    } catch (error) {
      message.error('启动返修失败');
    } finally {
      setLoading(false);
    }
  };

  const handleRestoreVersion = async (version: any, targetStage: 'draft' | 'final') => {
    if (!projectId || !currentChapter) return;

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const result: any = await chapterApi.restoreVersion(
        projectId,
        bookId,
        currentChapter.volumeNumber,
        currentChapter.chapterNumber,
        version.id,
        {
          targetStage,
          createVersionSnapshot: true,
        }
      );
      message.success(`已恢复为${targetStage === 'final' ? '终稿' : '草稿'}：${result?.restoredPath || ''}`);
      await refreshCurrentChapterArtifacts(bookId, currentChapter.volumeNumber, currentChapter.chapterNumber);
      await loadChapters();
    } catch (error) {
      message.error('恢复历史版本失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDiffChapter = async (data: any = {}) => {
    if (!projectId || !currentChapter) return;

    try {
      setDiffLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const result = await chapterApi.diff(
        projectId,
        bookId,
        currentChapter.volumeNumber,
        currentChapter.chapterNumber,
        data
      );
      setChapterDiff(result);
      setDiffModalVisible(true);
    } catch (error) {
      message.error('章节差异对比失败');
    } finally {
      setDiffLoading(false);
    }
  };

  const handleDiffDraftAndFinal = () => {
    handleDiffChapter({ fromStage: 'draft', toStage: 'final' });
  };

  const handleDiffVersionWithCurrent = (version: any) => {
    handleDiffChapter({
      fromVersionId: version.id,
      toStage: currentChapter?.isFinal ? 'final' : 'draft',
    });
  };

  const openReviewModal = (chapter: Chapter | any, decision = 'approved') => {
    setReviewTarget(chapter);
    reviewForm.setFieldsValue({
      decision,
      sourceStage: chapter?.isFinal ? 'final' : 'draft',
      reviewer: 'human',
      feedback: '',
    });
    setReviewModalVisible(true);
  };

  const handleHumanReview = async (values: any) => {
    if (!projectId || !reviewTarget) return;

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const targetVolume = reviewTarget.volumeNumber;
      const targetChapter = reviewTarget.chapterNumber;
      const result: any = await chapterApi.review(
        projectId,
        bookId,
        targetVolume,
        targetChapter,
        {
          decision: values.decision,
          sourceStage: values.sourceStage,
          reviewer: values.reviewer || 'human',
          feedback: values.feedback || '',
        }
      );
      const decisionText: Record<string, string> = {
        approved: '已批准',
        needs_revision: '已标记需修改',
        rejected: '已驳回',
      };
      message.success(decisionText[result?.decision] || '审查状态已更新');
      setReviewModalVisible(false);
      setReviewTarget(null);
      reviewForm.resetFields();

      if (
        currentChapter &&
        Number(currentChapter.volumeNumber) === Number(targetVolume) &&
        Number(currentChapter.chapterNumber) === Number(targetChapter)
      ) {
        await refreshCurrentChapterArtifacts(bookId, targetVolume, targetChapter);
      }
      await loadChapters();
    } catch (error) {
      message.error('保存人工审查失败');
    } finally {
      setLoading(false);
    }
  };

  const handleSaveChapterEdit = async () => {
    if (!projectId || !currentChapter) return;

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const result: any = await chapterApi.update(
        projectId,
        bookId,
        currentChapter.volumeNumber,
        currentChapter.chapterNumber,
        {
          chapterTitle: editTitle,
          content: editContent,
          editNote,
          editor: 'human',
          sourceStage: currentChapter.isFinal ? 'final' : 'draft',
          createVersionSnapshot: true,
          incrementVersion: true,
        }
      );
      message.success(`章节已保存：v${result?.versionBefore || '-'} -> v${result?.versionAfter || '-'}`);
      setEditingChapter(false);
      await refreshCurrentChapterArtifacts(bookId, currentChapter.volumeNumber, currentChapter.chapterNumber);
      await loadChapters();
    } catch (error) {
      message.error('保存章节失败');
    } finally {
      setLoading(false);
    }
  };

  const handleFinalize = async (chapter: Chapter) => {
    if (!projectId) return;

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const result: any = await chapterApi.finalize(
        projectId,
        bookId,
        chapter.volumeNumber,
        chapter.chapterNumber,
        {
          triggerMemoryExtraction: true,
          overwrite: true,
          createVersionSnapshot: true,
          finalizer: 'human',
          finalizeNote: '前端发布终稿',
        }
      );
      message.success(
        result?.memoryTaskId
          ? `终稿已发布：v${result?.versionAfter || '-'}，记忆摄取任务已启动：${result.memoryTaskId}`
          : `终稿已发布：v${result?.versionAfter || '-'}`
      );
      await loadChapters();
    } catch (error) {
      message.error('发布终稿失败');
    } finally {
      setLoading(false);
    }
  };

  const canFinalizeChapter = (chapter: Chapter) =>
    chapter.status === 'completed' && !chapter.isFinal;

  const handleBatchFinalize = async () => {
    if (!projectId || selectedChapters.length === 0) return;

    const publishable = selectedChapters.filter(canFinalizeChapter);
    if (publishable.length === 0) {
      message.warning('请选择可发布的草稿章节');
      return;
    }

    try {
      setLoading(true);
      const bookId = selectedBookId === 'default' ? (books[0]?.bookId || 'default') : selectedBookId;
      const result: any = await chapterApi.batchFinalize(projectId, bookId, {
        chapters: publishable.map((chapter) => ({
          volumeNumber: chapter.volumeNumber,
          chapterNumber: chapter.chapterNumber,
        })),
        triggerMemoryExtraction: true,
        overwrite: true,
        createVersionSnapshot: true,
        continueOnError: true,
        skipFinal: true,
        finalizer: 'human',
        finalizeNote: '前端批量发布终稿',
      });
      message.success(
        `批量发布完成：成功 ${result?.finalizedCount || 0}，跳过 ${result?.skippedCount || 0}，失败 ${result?.failedCount || 0}`
      );
      setSelectedChapterKeys([]);
      setSelectedChapters([]);
      await loadChapters();
    } catch (error) {
      message.error('批量发布终稿失败');
    } finally {
      setLoading(false);
    }
  };

  const columns = [
    {
      title: '卷号',
      dataIndex: 'volumeNumber',
      key: 'volumeNumber',
      width: 80,
    },
    {
      title: '章节号',
      dataIndex: 'chapterNumber',
      key: 'chapterNumber',
      width: 80,
    },
    {
      title: '章节标题',
      dataIndex: 'chapterTitle',
      key: 'chapterTitle',
    },
    {
      title: '字数',
      dataIndex: 'wordCount',
      key: 'wordCount',
      width: 100,
      render: (count: number) => count ? `${count}字` : '-',
    },
    {
      title: '质量评分',
      dataIndex: 'qualityScore',
      key: 'qualityScore',
      width: 120,
      render: (score: number) => {
        if (!score) return '-';
        const color = score >= 80 ? 'green' : score >= 60 ? 'orange' : 'red';
        return <Tag color={color}>{score}分</Tag>;
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => {
        const statusMap: Record<string, { color: string; text: string; icon: any }> = {
          generating: { color: 'processing', text: '生成中', icon: <ClockCircleOutlined /> },
          completed: { color: 'success', text: '已完成', icon: <CheckCircleOutlined /> },
          finalized: { color: 'gold', text: '已终稿', icon: <CheckCircleOutlined /> },
          failed: { color: 'error', text: '失败', icon: null },
        };
        const statusInfo = statusMap[status] || { color: 'default', text: status, icon: null };
        return (
          <Tag color={statusInfo.color} icon={statusInfo.icon}>
            {statusInfo.text}
          </Tag>
        );
      },
    },
    {
      title: '审查',
      dataIndex: 'reviewStatus',
      key: 'reviewStatus',
      width: 110,
      render: (_: string, record: Chapter) => {
        const status = record.humanReviewStatus || record.reviewStatus;
        const statusMap: Record<string, { color: string; text: string }> = {
          approved: { color: 'green', text: '已批准' },
          reviewed: { color: 'blue', text: '自动审查' },
          restored: { color: 'cyan', text: '已恢复' },
          manual_edited: { color: 'geekblue', text: '已手改' },
          needs_revision: { color: 'orange', text: '需修改' },
          rejected: { color: 'red', text: '已驳回' },
          draft: { color: 'default', text: '草稿' },
        };
        const statusInfo = statusMap[status || ''] || { color: 'default', text: status || '-' };
        return <Tag color={statusInfo.color}>{statusInfo.text}</Tag>;
      },
    },
    {
      title: '边界',
      dataIndex: 'boundaryCheck',
      key: 'boundaryCheck',
      width: 110,
      render: (boundaryCheck: any) => {
        if (!boundaryCheck) return '-';
        if (boundaryCheck.passed) return <Tag color="success">通过</Tag>;
        const blocking = boundaryCheck.blockingErrors?.length || 0;
        const warnings = boundaryCheck.warnings?.length || 0;
        return <Tag color={blocking > 0 ? 'error' : 'warning'}>{blocking > 0 ? `${blocking}阻塞` : `${warnings}警告`}</Tag>;
      },
    },
    {
      title: '返修',
      dataIndex: 'revisionHistory',
      key: 'revisionHistory',
      width: 90,
      render: (history: any[]) => {
        const count = history?.length || 0;
        return count > 0 ? <Tag color="purple">{count}轮</Tag> : '-';
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 180,
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: any, record: Chapter) => (
        <Space size="small">
          <Button
            type="link"
            icon={<EyeOutlined />}
            onClick={() => handleView(record)}
            disabled={!['completed', 'finalized'].includes(record.status)}
          >
            查看
          </Button>
          <Popconfirm
            title="发布为终稿"
            description="发布后会写入 final 目录，并自动启动记忆摄取任务。"
            okText="发布"
            cancelText="取消"
            onConfirm={() => handleFinalize(record)}
            disabled={record.isFinal || record.status === 'finalized'}
          >
            <Button
              type="link"
              icon={<SendOutlined />}
              disabled={record.isFinal || record.status === 'finalized' || record.status !== 'completed'}
            >
              发布终稿
            </Button>
          </Popconfirm>
          <Button
            type="link"
            icon={<EditOutlined />}
            onClick={() => openRevisionModal(record)}
            disabled={!['completed', 'finalized'].includes(record.status)}
          >
            返修
          </Button>
          <Button
            type="link"
            icon={<AuditOutlined />}
            onClick={() => openReviewModal(record)}
            disabled={!['completed', 'finalized'].includes(record.status)}
          >
            审查
          </Button>
        </Space>
      ),
    },
  ];

  const selectedPublishableCount = selectedChapters.filter(canFinalizeChapter).length;

  return (
    <div>
      <Card
        title="章节创作"
        extra={
          <Space>
            <Popconfirm
              title="批量发布终稿"
              description={`将发布 ${selectedPublishableCount} 个可发布草稿章节，已终稿或不可发布章节会被忽略。`}
              okText="发布"
              cancelText="取消"
              onConfirm={handleBatchFinalize}
              disabled={selectedPublishableCount === 0}
            >
              <Button
                icon={<SendOutlined />}
                disabled={selectedPublishableCount === 0}
                loading={loading}
              >
                批量发布终稿
                {selectedPublishableCount > 0 ? ` (${selectedPublishableCount})` : ''}
              </Button>
            </Popconfirm>
            {books.length > 0 && (
              <Select
                value={selectedBookId}
                style={{ width: 220 }}
                onChange={setSelectedBookId}
                options={[
                  { label: '最新书籍', value: 'default' },
                  ...books.map((book) => ({ label: book.bookTitle || book.bookId, value: book.bookId })),
                ]}
              />
            )}
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => setGenerateModalVisible(true)}
              disabled={books.length === 0}
            >
              生成章节
            </Button>
          </Space>
        }
      >
        {books.length === 0 ? (
          <Empty description="请先生成大纲" />
        ) : (
          <Table
            columns={columns}
            dataSource={chapters}
            loading={loading}
            rowKey="id"
            rowSelection={{
              selectedRowKeys: selectedChapterKeys,
              onChange: (keys, rows) => {
                setSelectedChapterKeys(keys);
                setSelectedChapters(rows as Chapter[]);
              },
              getCheckboxProps: (record: Chapter) => ({
                disabled: !canFinalizeChapter(record),
              }),
            }}
          />
        )}
      </Card>

      <Modal
        title="生成章节"
        open={generateModalVisible}
        onOk={() => form.submit()}
        onCancel={() => {
          setGenerateModalVisible(false);
          form.resetFields();
        }}
        confirmLoading={loading}
        width={600}
      >
        <Form
          form={form}
          layout="vertical"
          onFinish={handleGenerate}
          initialValues={{
            targetWordCount: 3000,
            useProjectSkills: true,
            usePreviousContext: true,
            autoReview: true,
          }}
        >
          <Form.Item
            name="volumeNumber"
            label="卷号"
            rules={[{ required: true, message: '请输入卷号' }]}
          >
            <InputNumber min={1} style={{ width: '100%' }} placeholder="例如：1" />
          </Form.Item>

          <Form.Item
            name="chapterNumber"
            label="章节号"
            rules={[{ required: true, message: '请输入章节号' }]}
          >
            <InputNumber min={1} style={{ width: '100%' }} placeholder="例如：1" />
          </Form.Item>

          <Form.Item
            name="chapterTitle"
            label="章节标题（可选）"
          >
            <Input placeholder="留空则使用大纲中的标题" />
          </Form.Item>

          <Form.Item
            name="targetWordCount"
            label="目标字数"
          >
            <InputNumber min={1000} max={5000} style={{ width: '100%' }} />
          </Form.Item>

          <Form.Item
            name="useProjectSkills"
            label="使用项目Skills指导"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            name="usePreviousContext"
            label="使用前文上下文"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>

          <Form.Item
            name="autoReview"
            label="自动审查"
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={revisionTarget ? `返修第${revisionTarget.chapterNumber}章` : '章节返修'}
        open={revisionModalVisible}
        onOk={() => revisionForm.submit()}
        onCancel={() => {
          setRevisionModalVisible(false);
          setRevisionTarget(null);
          revisionForm.resetFields();
        }}
        confirmLoading={loading}
        width={620}
      >
        <Form
          form={revisionForm}
          layout="vertical"
          onFinish={handleRevision}
          initialValues={{
            maxIterations: 1,
            includeBoundaryWarnings: true,
            createVersionSnapshot: true,
            sourceStage: 'draft',
          }}
        >
          <Form.Item name="sourceStage" label="返修来源">
            <Select
              options={[
                { label: '草稿', value: 'draft' },
                { label: '终稿', value: 'final' },
                { label: '自动选择', value: 'auto' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="userInstruction"
            label="返修要求"
            rules={[{ required: true, message: '请输入返修要求' }]}
          >
            <TextArea rows={4} placeholder="例如：删除越界内容，加强章末钩子，保留当前主线节奏" />
          </Form.Item>
          <Form.Item name="maxIterations" label="最大返修轮数">
            <InputNumber min={1} max={3} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="includeBoundaryWarnings" label="处理边界警告" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item name="createVersionSnapshot" label="返修前归档版本" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={reviewTarget ? `人工审查第${reviewTarget.chapterNumber}章` : '人工审查'}
        open={reviewModalVisible}
        onOk={() => reviewForm.submit()}
        onCancel={() => {
          setReviewModalVisible(false);
          setReviewTarget(null);
          reviewForm.resetFields();
        }}
        confirmLoading={loading}
        width={600}
      >
        <Form
          form={reviewForm}
          layout="vertical"
          onFinish={handleHumanReview}
          initialValues={{
            decision: 'approved',
            sourceStage: 'draft',
            reviewer: 'human',
          }}
        >
          <Form.Item name="decision" label="审查结论" rules={[{ required: true, message: '请选择审查结论' }]}>
            <Select
              options={[
                { label: '批准', value: 'approved' },
                { label: '要求修改', value: 'needs_revision' },
                { label: '驳回', value: 'rejected' },
              ]}
            />
          </Form.Item>
          <Form.Item name="sourceStage" label="审查对象">
            <Select
              options={[
                { label: '自动选择', value: 'auto' },
                { label: '草稿', value: 'draft' },
                { label: '终稿', value: 'final' },
              ]}
            />
          </Form.Item>
          <Form.Item name="reviewer" label="审查人">
            <Input placeholder="human" />
          </Form.Item>
          <Form.Item name="feedback" label="审查反馈">
            <TextArea rows={4} placeholder="记录批准理由、需修改点或驳回原因" />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        title={currentChapter ? `第${currentChapter.chapterNumber}章 ${currentChapter.chapterTitle}` : '章节详情'}
        width={800}
        open={viewDrawerVisible}
        onClose={() => {
          setViewDrawerVisible(false);
          setCurrentChapter(null);
          setCurrentContextPack(null);
          setEditingChapter(false);
          setEditTitle('');
          setEditContent('');
          setEditNote('');
        }}
      >
        {currentChapter && (
          <Space direction="vertical" size="large" style={{ width: '100%' }}>
            {/* 基本信息 */}
            <Card title="基本信息" size="small">
              <Descriptions column={2} size="small">
                <Descriptions.Item label="卷号">{currentChapter.volumeNumber}</Descriptions.Item>
                <Descriptions.Item label="章节号">{currentChapter.chapterNumber}</Descriptions.Item>
                <Descriptions.Item label="字数">{currentChapter.wordCount}字</Descriptions.Item>
                <Descriptions.Item label="阶段">
                  {currentChapter.isFinal ? <Tag color="gold">终稿</Tag> : <Tag>草稿</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="质量评分">
                  {currentChapter.qualityScore ? `${currentChapter.qualityScore}分` : '-'}
                </Descriptions.Item>
                <Descriptions.Item label="返修复核">
                  {renderRevisionQualityTag(getRevisionQuality(currentChapter))}
                </Descriptions.Item>
                <Descriptions.Item label="返修次数">
                  {currentChapter.revisionHistory?.length || 0}
                </Descriptions.Item>
                <Descriptions.Item label="人工审查">
                  {(() => {
                    const status = currentChapter.humanReviewStatus || currentChapter.reviewStatus;
                    const statusMap: Record<string, { color: string; text: string }> = {
                      approved: { color: 'green', text: '已批准' },
                      reviewed: { color: 'blue', text: '自动审查' },
                      restored: { color: 'cyan', text: '已恢复' },
                      manual_edited: { color: 'geekblue', text: '已手改' },
                      needs_revision: { color: 'orange', text: '需修改' },
                      rejected: { color: 'red', text: '已驳回' },
                    };
                    const statusInfo = statusMap[status || ''] || { color: 'default', text: status || '-' };
                    return <Tag color={statusInfo.color}>{statusInfo.text}</Tag>;
                  })()}
                </Descriptions.Item>
                {currentChapter.humanReviewedAt && (
                  <Descriptions.Item label="审查时间">{currentChapter.humanReviewedAt}</Descriptions.Item>
                )}
                {currentChapter.finalizedAt && (
                  <Descriptions.Item label="终稿时间">{currentChapter.finalizedAt}</Descriptions.Item>
                )}
                {currentChapter.path && (
                  <Descriptions.Item label="文件路径" span={2}>{currentChapter.path}</Descriptions.Item>
                )}
              </Descriptions>
              <Space style={{ marginTop: 12 }}>
                <Button
                  size="small"
                  icon={<AuditOutlined />}
                  onClick={() => openReviewModal(currentChapter, 'approved')}
                >
                  批准
                </Button>
                <Button
                  size="small"
                  onClick={() => openReviewModal(currentChapter, 'needs_revision')}
                >
                  要求修改
                </Button>
                <Button
                  size="small"
                  icon={<DiffOutlined />}
                  loading={diffLoading}
                  onClick={handleDiffDraftAndFinal}
                >
                  草稿/终稿对比
                </Button>
                <Button
                  size="small"
                  danger
                  onClick={() => openReviewModal(currentChapter, 'rejected')}
                >
                  驳回
                </Button>
              </Space>
            </Card>

            {(currentChapter.humanReviewHistory || []).length > 0 && (
              <Card title="人工审查历史" size="small">
                <List
                  size="small"
                  dataSource={currentChapter.humanReviewHistory}
                  renderItem={(item: any) => (
                    <List.Item>
                      <Space direction="vertical" size={0}>
                        <Space>
                          <Tag color={item.decision === 'approved' ? 'green' : item.decision === 'rejected' ? 'red' : 'orange'}>
                            {item.decision}
                          </Tag>
                          <span>{item.reviewer || 'human'}</span>
                          <span>{item.reviewedAt || item.reviewed_at}</span>
                        </Space>
                        {item.feedback && <span>{item.feedback}</span>}
                      </Space>
                    </List.Item>
                  )}
                />
              </Card>
            )}

            {/* 审查结果 */}
            {currentChapter.review && (
              <Card title="审查结果" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div>
                    <strong>总分：</strong>
                    {currentChapter.review.totalScore}分 / 50分
                  </div>
                  <Progress
                    percent={(currentChapter.review.totalScore / 50) * 100}
                    strokeColor={{
                      '0%': '#108ee9',
                      '100%': '#87d068',
                    }}
                  />
                  <Descriptions column={1} size="small" bordered>
                    <Descriptions.Item label="风格一致性">
                      {currentChapter.review.styleConsistency}/10
                    </Descriptions.Item>
                    <Descriptions.Item label="技巧运用">
                      {currentChapter.review.skillApplication}/10
                    </Descriptions.Item>
                    <Descriptions.Item label="质量水平">
                      {currentChapter.review.qualityLevel}/10
                    </Descriptions.Item>
                    <Descriptions.Item label="连续性">
                      {currentChapter.review.continuity}/10
                    </Descriptions.Item>
                    <Descriptions.Item label="结构完整性">
                      {currentChapter.review.structure}/10
                    </Descriptions.Item>
                  </Descriptions>
                </Space>
              </Card>
            )}

            {currentChapter.boundaryCheck && (
              <Card title="章节边界检查" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Alert
                    type={currentChapter.boundaryCheck.passed ? 'success' : 'error'}
                    showIcon
                    message={currentChapter.boundaryCheck.passed ? '边界检查通过' : '发现章节越界风险'}
                    description={`阻塞 ${currentChapter.boundaryCheck.blockingErrors?.length || 0} 项，警告 ${currentChapter.boundaryCheck.warnings?.length || 0} 项，提示 ${currentChapter.boundaryCheck.info?.length || 0} 项`}
                  />
                  {(currentChapter.boundaryCheck.blockingErrors || []).map((item: any, index: number) => (
                    <Alert
                      key={`blocking-${index}`}
                      type="error"
                      showIcon
                      message={item.message}
                      description={item.suggestion || item.evidence}
                    />
                  ))}
                  {(currentChapter.boundaryCheck.warnings || []).map((item: any, index: number) => (
                    <Alert
                      key={`warning-${index}`}
                      type="warning"
                      showIcon
                      message={item.message}
                      description={item.suggestion || item.evidence}
                    />
                  ))}
                  {currentChapter.boundaryCheck.boundaryControl && (
                    <Descriptions column={1} size="small" bordered>
                      <Descriptions.Item label="本章核心目标">
                        {currentChapter.boundaryCheck.boundaryControl.coreGoal || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="停止点">
                        {currentChapter.boundaryCheck.boundaryControl.stopPoint || '-'}
                      </Descriptions.Item>
                      <Descriptions.Item label="章末钩子">
                        {currentChapter.boundaryCheck.boundaryControl.endingHook || '-'}
                      </Descriptions.Item>
                    </Descriptions>
                  )}
                </Space>
              </Card>
            )}

            {Object.keys(getRevisionQuality(currentChapter)).length > 0 && (
              <Card title="返修质量复核" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  {(() => {
                    const quality = getRevisionQuality(currentChapter);
                    const status = readAny(quality, 'status');
                    const issues = Array.isArray(readAny(quality, 'issues')) ? readAny(quality, 'issues') : [];
                    const suggestions = Array.isArray(readAny(quality, 'suggestions')) ? readAny(quality, 'suggestions') : [];
                    return (
                      <>
                        <Alert
                          type={status === 'passed' ? 'success' : status === 'unavailable' ? 'warning' : 'info'}
                          showIcon
                          message={`返修质量复核：${status || 'unknown'}`}
                          description={readAny(quality, 'summary') || '暂无复核摘要'}
                        />
                        <Descriptions column={3} size="small" bordered>
                          <Descriptions.Item label="评分">{readAny(quality, 'score') ?? '-'}</Descriptions.Item>
                          <Descriptions.Item label="最低通过分">{readAny(quality, 'minScore', 'min_score') ?? '-'}</Descriptions.Item>
                          <Descriptions.Item label="评级">{readAny(quality, 'overallRating', 'overall_rating') || '-'}</Descriptions.Item>
                        </Descriptions>
                        {issues.map((issue: any, index: number) => (
                          <Alert
                            key={`revision-quality-issue-${index}`}
                            type={issue.severity === 'error' ? 'error' : 'warning'}
                            showIcon
                            message={issue.message || issue.description || '返修质量问题'}
                            description={issue.suggestion || issue.evidence || ''}
                          />
                        ))}
                        {suggestions.length > 0 && (
                          <List
                            size="small"
                            header="复核建议"
                            dataSource={suggestions}
                            renderItem={(item: any) => <List.Item>{String(item)}</List.Item>}
                          />
                        )}
                      </>
                    );
                  })()}
                </Space>
              </Card>
            )}

            {(currentChapter.revisionHistory || []).length > 0 && (
              <Card title="返修历史" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  {currentChapter.revisionHistory.map((item: any, index: number) => (
                    <Card key={`revision-${index}`} size="small">
                      <Descriptions column={1} size="small">
                        <Descriptions.Item label="轮次">第{item.iteration}轮</Descriptions.Item>
                        <Descriptions.Item label="时间">{item.revisedAt || item.revised_at}</Descriptions.Item>
                        <Descriptions.Item label="触发类型">
                          {(item.triggerTypes || item.trigger_types || []).map((type: string) => (
                            <Tag key={type} color="purple">{type}</Tag>
                          ))}
                        </Descriptions.Item>
                        <Descriptions.Item label="字数变化">
                          {`${item.wordCountBefore || item.word_count_before} -> ${item.wordCountAfter || item.word_count_after}`}
                        </Descriptions.Item>
                        <Descriptions.Item label="质量复核">
                          {renderRevisionQualityTag(readAny(item, 'qualityReview', 'quality_review'))}
                        </Descriptions.Item>
                      </Descriptions>
                    </Card>
                  ))}
                </Space>
              </Card>
            )}

            {(chapterVersions.length > 0 || chapterReviews.length > 0) && (
              <Card title="版本与审查归档" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  {chapterVersions.length > 0 && (
                    <Card title="历史版本" size="small">
                      <List
                        size="small"
                        dataSource={chapterVersions}
                        renderItem={(item: any) => (
                          <List.Item
                            actions={[
                              <Button
                                key="diff-current"
                                type="link"
                                size="small"
                                icon={<DiffOutlined />}
                                loading={diffLoading}
                                onClick={() => handleDiffVersionWithCurrent(item)}
                              >
                                与当前对比
                              </Button>,
                              <Popconfirm
                                key="restore-draft"
                                title="恢复为草稿"
                                description="会先归档当前草稿，再把该版本恢复为新的草稿。"
                                okText="恢复"
                                cancelText="取消"
                                onConfirm={() => handleRestoreVersion(item, 'draft')}
                              >
                                <Button type="link" size="small">恢复为草稿</Button>
                              </Popconfirm>,
                              <Popconfirm
                                key="restore-final"
                                title="恢复为终稿"
                                description="会先归档当前终稿，再把该版本发布为新的终稿。"
                                okText="恢复"
                                cancelText="取消"
                                onConfirm={() => handleRestoreVersion(item, 'final')}
                              >
                                <Button type="link" size="small">恢复为终稿</Button>
                              </Popconfirm>,
                            ]}
                          >
                            <Space direction="vertical" size={0}>
                              <Space>
                                <HistoryOutlined />
                                <strong>{item.id}</strong>
                                <Tag>v{item.version}</Tag>
                                <span>{item.wordCount}字</span>
                              </Space>
                              <span>{item.path}</span>
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  )}
                  {chapterReviews.length > 0 && (
                    <Card title="返修/审查报告" size="small">
                      <List
                        size="small"
                        dataSource={chapterReviews}
                        renderItem={(item: any) => (
                          <List.Item>
                            <Space direction="vertical" size={0}>
                              <Space>
                                <Tag color="blue">{item.reviewType || 'review'}</Tag>
                                <strong>{item.id}</strong>
                                <span>{item.updatedAt}</span>
                              </Space>
                              <span>
                                {`v${item.versionBefore || '-'} -> v${item.versionAfter || '-'}，${item.wordCountBefore || 0} -> ${item.wordCountAfter || 0}字`}
                              </span>
                              <span>{item.path}</span>
                            </Space>
                          </List.Item>
                        )}
                      />
                    </Card>
                  )}
                </Space>
              </Card>
            )}

            {currentContextPack && (
              <Card title="检索上下文包" size="small">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Alert
                    type="info"
                    showIcon
                    message={currentContextPack.path}
                    description={`索引文档 ${currentContextPack.sources?.documents_indexed || 0} 个，关键词结果 ${currentContextPack.sources?.keyword_results || 0} 条，图谱节点 ${currentContextPack.sources?.graph_nodes || 0} 个`}
                  />
                  {(currentContextPack.retrieval_results || []).slice(0, 5).map((item: any) => (
                    <Card key={item.doc_id} size="small">
                      <Space direction="vertical" style={{ width: '100%' }}>
                        <Space>
                          <Tag color="blue">{item.source_type}</Tag>
                          <strong>{item.title}</strong>
                          <Tag>{item.score}</Tag>
                        </Space>
                        <div>{item.snippet}</div>
                      </Space>
                    </Card>
                  ))}
                </Space>
              </Card>
            )}

            {/* 正文内容 */}
            <Card
              title="正文内容"
              size="small"
              extra={
                editingChapter ? (
                  <Space>
                    <Button
                      size="small"
                      icon={<SaveOutlined />}
                      type="primary"
                      loading={loading}
                      onClick={handleSaveChapterEdit}
                    >
                      保存
                    </Button>
                    <Button
                      size="small"
                      icon={<CloseOutlined />}
                      onClick={() => {
                        setEditingChapter(false);
                        setEditTitle(currentChapter.chapterTitle || '');
                        setEditContent(currentChapter.content || '');
                        setEditNote('');
                      }}
                    >
                      取消
                    </Button>
                  </Space>
                ) : (
                  <Button
                    size="small"
                    icon={<EditOutlined />}
                    onClick={() => {
                      setEditingChapter(true);
                      setEditTitle(currentChapter.chapterTitle || '');
                      setEditContent(currentChapter.content || '');
                      setEditNote('');
                    }}
                  >
                    编辑
                  </Button>
                )
              }
            >
              {editingChapter ? (
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Input
                    value={editTitle}
                    onChange={(event) => setEditTitle(event.target.value)}
                    placeholder="章节标题"
                  />
                  <TextArea
                    value={editNote}
                    onChange={(event) => setEditNote(event.target.value)}
                    rows={2}
                    placeholder="编辑说明"
                  />
                  <TextArea
                    value={editContent}
                    onChange={(event) => setEditContent(event.target.value)}
                    rows={22}
                    style={{ fontFamily: 'inherit' }}
                  />
                </Space>
              ) : (
                <TextArea
                  value={currentChapter.content}
                  rows={20}
                  readOnly
                  style={{ fontFamily: 'inherit' }}
                />
              )}
            </Card>
          </Space>
        )}
      </Drawer>

      <Modal
        title="章节差异对比"
        open={diffModalVisible}
        footer={null}
        width={960}
        onCancel={() => setDiffModalVisible(false)}
      >
        {chapterDiff ? (
          <Space direction="vertical" style={{ width: '100%' }} size="middle">
            <Descriptions column={2} size="small" bordered>
              <Descriptions.Item label="对比来源" span={2}>
                {chapterDiff.from?.path}{' -> '}{chapterDiff.to?.path}
              </Descriptions.Item>
              <Descriptions.Item label="左侧版本">
                <Space>
                  <Tag>{chapterDiff.from?.stage}</Tag>
                  {chapterDiff.from?.versionId ? <Tag color="purple">{chapterDiff.from.versionId}</Tag> : null}
                  <span>v{chapterDiff.from?.version || '-'}</span>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="右侧版本">
                <Space>
                  <Tag color={chapterDiff.to?.stage === 'final' ? 'gold' : 'default'}>{chapterDiff.to?.stage}</Tag>
                  {chapterDiff.to?.versionId ? <Tag color="purple">{chapterDiff.to.versionId}</Tag> : null}
                  <span>v{chapterDiff.to?.version || '-'}</span>
                </Space>
              </Descriptions.Item>
              <Descriptions.Item label="标题变化">
                {chapterDiff.summary?.titleChanged ? <Tag color="orange">已变化</Tag> : <Tag>无变化</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="内容变化">
                {chapterDiff.summary?.contentChanged ? <Tag color="orange">已变化</Tag> : <Tag>无变化</Tag>}
              </Descriptions.Item>
              <Descriptions.Item label="字数">
                {chapterDiff.summary?.fromWordCount || 0}{' -> '}{chapterDiff.summary?.toWordCount || 0}
                <Tag color={(chapterDiff.summary?.wordCountDelta || 0) >= 0 ? 'green' : 'red'} style={{ marginLeft: 8 }}>
                  {chapterDiff.summary?.wordCountDelta || 0}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="行变化">
                <Space wrap>
                  <Tag color="green">新增 {chapterDiff.summary?.addedLines || 0}</Tag>
                  <Tag color="red">删除 {chapterDiff.summary?.removedLines || 0}</Tag>
                  <Tag color="orange">修改 {chapterDiff.summary?.changedLines || 0}</Tag>
                  <Tag>相同 {chapterDiff.summary?.unchangedLines || 0}</Tag>
                </Space>
              </Descriptions.Item>
            </Descriptions>

            <List
              size="small"
              bordered
              dataSource={chapterDiff.hunks || []}
              locale={{ emptyText: '没有差异' }}
              renderItem={(item: any, index: number) => {
                const colorMap: Record<string, string> = {
                  equal: 'default',
                  added: 'green',
                  removed: 'red',
                  changed: 'orange',
                };
                return (
                  <List.Item key={`${item.type}-${index}`}>
                    <Space direction="vertical" style={{ width: '100%' }} size={4}>
                      <Space>
                        <Tag color={colorMap[item.type] || 'default'}>{item.type}</Tag>
                        <span>
                          {item.oldLineNumber || '-'}{' -> '}{item.newLineNumber || '-'}
                        </span>
                      </Space>
                      {item.type === 'changed' ? (
                        <Row gutter={12}>
                          <Col span={12}>
                            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{item.oldText}</pre>
                          </Col>
                          <Col span={12}>
                            <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{item.newText}</pre>
                          </Col>
                        </Row>
                      ) : (
                        <pre style={{ margin: 0, whiteSpace: 'pre-wrap' }}>{item.newText ?? item.oldText}</pre>
                      )}
                    </Space>
                  </List.Item>
                );
              }}
            />
          </Space>
        ) : (
          <Empty description="暂无差异数据" />
        )}
      </Modal>
    </div>
  );
};

export default ChapterWriter;
