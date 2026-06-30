import React, { useState, useEffect } from 'react';
import {
  Card,
  Tabs,
  Button,
  Modal,
  Drawer,
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
import { BookOutlined, UserOutlined, GlobalOutlined, FileTextOutlined, PlusOutlined, EditOutlined, CheckCircleOutlined, ToolOutlined, LockOutlined, UnlockOutlined, HistoryOutlined, SaveOutlined } from '@ant-design/icons';
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
  coreGoal?: string;
  mustWrite?: string[] | string;
  allowedProgress?: string[] | string;
  mustNotWrite?: string[] | string;
  reservedForFuture?: Record<string, string> | string[] | string;
  stopPoint?: string;
  endingHook?: string;
  outlineReviewStatus?: string;
  outlineReviewNote?: string;
  outlineReviewer?: string;
  outlineReviewedAt?: string;
  [key: string]: any;
}

interface ChapterEditRef {
  volumeIndex: number;
  chapterIndex: number;
  volumeNumber: number;
  chapterNumber: number;
  chapterTitle: string;
}

const readField = (record: Record<string, any> | undefined, camelKey: string, snakeKey: string) => {
  if (!record) return undefined;
  return record[camelKey] ?? record[snakeKey];
};

const textValue = (value: any) => (value === undefined || value === null ? '' : String(value));

const normalizeList = (value: any): string[] => {
  if (Array.isArray(value)) {
    return value.map((item) => textValue(item).trim()).filter(Boolean);
  }
  if (value && typeof value === 'object') {
    return Object.values(value).map((item) => textValue(item).trim()).filter(Boolean);
  }
  return textValue(value)
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);
};

const normalizeReserved = (value: any): Array<[string, string]> => {
  if (!value) return [];
  if (Array.isArray(value)) {
    return value
      .map((item, index) => [`chapter_${index + 1}`, textValue(item).trim()] as [string, string])
      .filter(([, item]) => Boolean(item));
  }
  if (typeof value === 'object') {
    return Object.entries(value)
      .map(([key, item]) => [key, textValue(item).trim()] as [string, string])
      .filter(([, item]) => Boolean(item));
  }
  return textValue(value)
    .split(/\r?\n/)
    .map((line, index) => {
      const trimmed = line.trim();
      const separator = trimmed.search(/[:：=]/);
      if (separator > 0) {
        return [trimmed.slice(0, separator).trim(), trimmed.slice(separator + 1).trim()] as [string, string];
      }
      return [`chapter_${index + 1}`, trimmed] as [string, string];
    })
    .filter(([, item]) => Boolean(item));
};

const toMultiline = (value: any) => normalizeList(value).join('\n');

const reservedToMultiline = (value: any) =>
  normalizeReserved(value).map(([key, item]) => `${key}: ${item}`).join('\n');

const parseMultilineList = (value: any) =>
  textValue(value)
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean);

const parseReservedMap = (value: any) => {
  const result: Record<string, string> = {};
  textValue(value)
    .split(/\r?\n/)
    .map((item) => item.trim())
    .filter(Boolean)
    .forEach((line, index) => {
      const separator = line.search(/[:：=]/);
      const key = separator > 0 ? line.slice(0, separator).trim() : `chapter_${index + 1}`;
      const item = separator > 0 ? line.slice(separator + 1).trim() : line;
      if (key && item) {
        result[key] = item;
      }
    });
  return result;
};

const cloneEditableOutline = (source: any) => {
  const editable = JSON.parse(JSON.stringify(source || {}));
  delete editable.outlinePath;
  delete editable.projectSoul;
  delete editable.projectSoulPath;
  delete editable.projectSoulGovernance;
  delete editable.outlineGovernance;
  return editable;
};

const isChapterBoundaryComplete = (chapter: Chapter) => (
  textValue(readField(chapter, 'coreGoal', 'core_goal')).trim() !== ''
  && normalizeList(readField(chapter, 'mustWrite', 'must_write')).length > 0
  && normalizeList(readField(chapter, 'allowedProgress', 'allowed_progress')).length > 0
  && normalizeList(readField(chapter, 'mustNotWrite', 'must_not_write')).length > 0
  && normalizeReserved(readField(chapter, 'reservedForFuture', 'reserved_for_future')).length > 0
  && textValue(readField(chapter, 'stopPoint', 'stop_point')).trim() !== ''
  && textValue(readField(chapter, 'endingHook', 'ending_hook')).trim() !== ''
);

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
  const [outlineVersions, setOutlineVersions] = useState<any[]>([]);
  const [outlineVersionOpen, setOutlineVersionOpen] = useState(false);
  const [outlineVersionPreview, setOutlineVersionPreview] = useState<any>(null);
  const [soulVersions, setSoulVersions] = useState<any[]>([]);
  const [soulVersionOpen, setSoulVersionOpen] = useState(false);
  const [soulVersionPreview, setSoulVersionPreview] = useState<any>(null);
  const [chapterBoundaryOpen, setChapterBoundaryOpen] = useState(false);
  const [editingChapterRef, setEditingChapterRef] = useState<ChapterEditRef | null>(null);
  const [savingChapterBoundary, setSavingChapterBoundary] = useState(false);
  const [soulGovernanceLoading, setSoulGovernanceLoading] = useState(false);
  const [outlineGovernanceLoading, setOutlineGovernanceLoading] = useState(false);
  const [form] = Form.useForm();
  const [chapterBoundaryForm] = Form.useForm();

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
    const editableOutline = cloneEditableOutline(outline);
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

  const runOutlineGovernance = async (action: 'lock' | 'unlock' | 'approve') => {
    if (!projectId || !outline) return;
    const bookId = selectedBookId || outline.bookId || 'default';
    try {
      setOutlineGovernanceLoading(true);
      const payload = { actor: 'human', reviewer: 'human', note: `OutlineEditor ${action}`, lock: action === 'approve' };
      let result: any;
      if (action === 'lock') {
        result = await outlineApi.lock(projectId, bookId, payload);
        message.success('大纲已锁定');
      } else if (action === 'unlock') {
        result = await outlineApi.unlock(projectId, bookId, payload);
        message.success('大纲已解锁');
      } else {
        result = await outlineApi.approve(projectId, bookId, payload);
        message.success('大纲已批准并锁定');
      }
      if (result?.outline) {
        setOutline(result.outline);
      } else {
        await loadOutline();
      }
    } catch (error) {
      message.error('更新大纲治理状态失败');
    } finally {
      setOutlineGovernanceLoading(false);
    }
  };

  const openOutlineVersions = async () => {
    if (!projectId || !outline) return;
    try {
      setOutlineGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      const versions = await outlineApi.getVersions(projectId, bookId);
      setOutlineVersions(versions || []);
      setOutlineVersionOpen(true);
    } catch (error) {
      message.error('加载大纲版本失败');
    } finally {
      setOutlineGovernanceLoading(false);
    }
  };

  const previewOutlineVersion = async (version: any) => {
    if (!projectId || !outline) return;
    try {
      setOutlineGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      const detail = await outlineApi.getVersion(projectId, bookId, version.id);
      setOutlineVersionPreview(detail);
    } catch (error) {
      message.error('加载大纲版本内容失败');
    } finally {
      setOutlineGovernanceLoading(false);
    }
  };

  const restoreOutlineVersion = async (version: any, overrideOutlineLock = false) => {
    if (!projectId || !outline) return;
    try {
      setOutlineGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      await outlineApi.restoreVersion(projectId, bookId, version.id, {
        actor: 'human',
        note: `Restore outline from ${version.id}`,
        createVersionSnapshot: true,
        overrideOutlineLock,
      });
      message.success('大纲版本已恢复');
      setOutlineVersionPreview(null);
      setOutlineVersionOpen(false);
      await loadOutline();
    } catch (error) {
      message.error('恢复大纲版本失败');
    } finally {
      setOutlineGovernanceLoading(false);
    }
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

  const previewSoulVersion = async (version: any) => {
    if (!projectId || !outline) return;
    try {
      setSoulGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      const detail = await outlineApi.getSoulVersion(projectId, bookId, version.id);
      setSoulVersionPreview(detail);
    } catch (error) {
      message.error('加载Project Soul版本内容失败');
    } finally {
      setSoulGovernanceLoading(false);
    }
  };

  const restoreSoulVersion = async (version: any, overrideSoulLock = false) => {
    if (!projectId || !outline) return;
    try {
      setSoulGovernanceLoading(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      await outlineApi.restoreSoulVersion(projectId, bookId, version.id, {
        actor: 'human',
        note: `Restore Project Soul from ${version.id}`,
        createVersionSnapshot: true,
        overrideSoulLock,
      });
      message.success('Project Soul版本已恢复');
      setSoulVersionPreview(null);
      setSoulVersionOpen(false);
      await loadOutline();
    } catch (error) {
      message.error('恢复Project Soul版本失败');
    } finally {
      setSoulGovernanceLoading(false);
    }
  };

  const soulGovernance = outline?.projectSoulGovernance || outline?.governance || {};
  const outlineGovernance = outline?.outlineGovernance || {};

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

  const openChapterBoundaryEditor = (
    volumeNumber: number,
    volumeIndex: number,
    chapter: Chapter,
    chapterIndex: number,
  ) => {
    if (!outline || outlineGovernance.locked) return;
    const ref = {
      volumeIndex,
      chapterIndex,
      volumeNumber,
      chapterNumber: chapter.chapterNumber,
      chapterTitle: chapter.chapterTitle,
    };
    setEditingChapterRef(ref);
    chapterBoundaryForm.setFieldsValue({
      chapterTitle: chapter.chapterTitle,
      plotGoal: chapter.plotGoal,
      targetWordCount: chapter.targetWordCount || 3000,
      coreGoal: textValue(readField(chapter, 'coreGoal', 'core_goal')),
      mustWrite: toMultiline(readField(chapter, 'mustWrite', 'must_write')),
      allowedProgress: toMultiline(readField(chapter, 'allowedProgress', 'allowed_progress')),
      mustNotWrite: toMultiline(readField(chapter, 'mustNotWrite', 'must_not_write')),
      reservedForFuture: reservedToMultiline(readField(chapter, 'reservedForFuture', 'reserved_for_future')),
      stopPoint: textValue(readField(chapter, 'stopPoint', 'stop_point')),
      endingHook: textValue(readField(chapter, 'endingHook', 'ending_hook')),
      outlineReviewStatus: textValue(readField(chapter, 'outlineReviewStatus', 'outline_review_status')) || 'pending_review',
      outlineReviewNote: textValue(readField(chapter, 'outlineReviewNote', 'outline_review_note')),
    });
    setChapterBoundaryOpen(true);
  };

  const handleSaveChapterBoundary = async () => {
    if (!projectId || !outline || !editingChapterRef) return;
    if (outlineGovernance.locked) {
      message.error('大纲已锁定，请先解锁后再编辑章节边界');
      return;
    }

    try {
      const values = await chapterBoundaryForm.validateFields();
      const editableOutline = cloneEditableOutline(outline);
      const volumes = Array.isArray(editableOutline.volumes) ? editableOutline.volumes : [];
      const targetVolume = volumes[editingChapterRef.volumeIndex];
      const chapters = Array.isArray(targetVolume?.chapters) ? targetVolume.chapters : [];
      const targetChapter = chapters[editingChapterRef.chapterIndex];
      if (!targetChapter) {
        message.error('未找到要保存的章节');
        return;
      }

      const now = new Date().toISOString();
      targetChapter.chapterTitle = textValue(values.chapterTitle).trim();
      targetChapter.plotGoal = textValue(values.plotGoal).trim();
      targetChapter.targetWordCount = Number(values.targetWordCount || targetChapter.targetWordCount || 3000);
      targetChapter.coreGoal = textValue(values.coreGoal).trim();
      targetChapter.mustWrite = parseMultilineList(values.mustWrite);
      targetChapter.allowedProgress = parseMultilineList(values.allowedProgress);
      targetChapter.mustNotWrite = parseMultilineList(values.mustNotWrite);
      targetChapter.reservedForFuture = parseReservedMap(values.reservedForFuture);
      targetChapter.stopPoint = textValue(values.stopPoint).trim();
      targetChapter.endingHook = textValue(values.endingHook).trim();
      targetChapter.outlineReviewStatus = values.outlineReviewStatus || 'pending_review';
      targetChapter.outlineReviewNote = textValue(values.outlineReviewNote).trim();
      targetChapter.outlineReviewer = 'human';
      targetChapter.outlineReviewedAt = now;

      setSavingChapterBoundary(true);
      const bookId = selectedBookId || outline.bookId || 'default';
      await outlineApi.update(projectId, bookId, {
        outline: editableOutline,
        editNote: `Structured chapter outline edit: volume ${editingChapterRef.volumeNumber}, chapter ${editingChapterRef.chapterNumber}`,
        editor: 'human',
        createVersionSnapshot: true,
      });
      message.success('章节结构化信息已保存');
      setChapterBoundaryOpen(false);
      setEditingChapterRef(null);
      chapterBoundaryForm.resetFields();
      await loadOutline();
    } catch (error) {
      message.error('保存章节结构化信息失败');
    } finally {
      setSavingChapterBoundary(false);
    }
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

  const renderListTags = (items: string[]) => {
    if (!items.length) return <Tag color="warning">未填写</Tag>;
    return (
      <Space wrap size={[0, 4]}>
        {items.slice(0, 4).map((item, index) => (
          <Tag key={`${item}-${index}`}>{item}</Tag>
        ))}
        {items.length > 4 && <Tag>+{items.length - 4}</Tag>}
      </Space>
    );
  };

  const renderReservedTags = (entries: Array<[string, string]>) => {
    if (!entries.length) return <Tag color="warning">未填写</Tag>;
    return (
      <Space direction="vertical" size={2}>
        {entries.slice(0, 4).map(([key, item]) => (
          <span key={key}>
            <Tag>{key}</Tag>
            {item}
          </span>
        ))}
        {entries.length > 4 && <Tag>+{entries.length - 4}</Tag>}
      </Space>
    );
  };

  const renderOutlineReviewTag = (status: string) => {
    const colorMap: Record<string, string> = {
      approved: 'success',
      needs_revision: 'error',
      pending_review: 'warning',
      draft: 'default',
    };
    return <Tag color={colorMap[status] || 'default'}>{status || 'pending_review'}</Tag>;
  };

  const renderChapterList = (volumeNumber: number, chapters: Chapter[], volumeIndex: number) => (
    <Collapse>
      {chapters.map((chapter, chapterIndex) => {
        const complete = isChapterBoundaryComplete(chapter);
        const reviewStatus = textValue(readField(chapter, 'outlineReviewStatus', 'outline_review_status')) || 'pending_review';
        return (
          <Panel
            key={chapter.chapterNumber}
            header={`第${chapter.chapterNumber}章 ${chapter.chapterTitle}`}
            extra={
              <Space onClick={(event) => event.stopPropagation()}>
                <Tag color={complete ? 'success' : 'warning'}>{complete ? '边界完整' : '边界缺失'}</Tag>
                {renderOutlineReviewTag(reviewStatus)}
              </Space>
            }
          >
            <Space direction="vertical" style={{ width: '100%' }}>
              <Descriptions column={2} size="small" bordered>
                <Descriptions.Item label="剧情目标" span={2}>{chapter.plotGoal}</Descriptions.Item>
                <Descriptions.Item label="目标字数">{chapter.targetWordCount}字</Descriptions.Item>
                <Descriptions.Item label="核心目标">
                  {textValue(readField(chapter, 'coreGoal', 'core_goal')) || <Tag color="warning">未填写</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="必须写" span={2}>
                  {renderListTags(normalizeList(readField(chapter, 'mustWrite', 'must_write')))}
                </Descriptions.Item>
                <Descriptions.Item label="可铺垫但不完成" span={2}>
                  {renderListTags(normalizeList(readField(chapter, 'allowedProgress', 'allowed_progress')))}
                </Descriptions.Item>
                <Descriptions.Item label="禁止提前写" span={2}>
                  {renderListTags(normalizeList(readField(chapter, 'mustNotWrite', 'must_not_write')))}
                </Descriptions.Item>
                <Descriptions.Item label="后续章纲保护" span={2}>
                  {renderReservedTags(normalizeReserved(readField(chapter, 'reservedForFuture', 'reserved_for_future')))}
                </Descriptions.Item>
                <Descriptions.Item label="停止点">
                  {textValue(readField(chapter, 'stopPoint', 'stop_point')) || <Tag color="warning">未填写</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="章末钩子">
                  {textValue(readField(chapter, 'endingHook', 'ending_hook')) || <Tag color="warning">未填写</Tag>}
                </Descriptions.Item>
                <Descriptions.Item label="确认备注" span={2}>
                  {textValue(readField(chapter, 'outlineReviewNote', 'outline_review_note')) || '-'}
                </Descriptions.Item>
              </Descriptions>
              <Space wrap>
                <Button type="primary" onClick={() => openChapterWorkspace(volumeNumber, chapter)}>
                  鍒涗綔/鏌ョ湅鏈珷
                </Button>
                <Button
                  icon={<EditOutlined />}
                  disabled={!!outlineGovernance.locked}
                  onClick={() => openChapterBoundaryEditor(volumeNumber, volumeIndex, chapter, chapterIndex)}
                >
                  结构化编辑
                </Button>
              </Space>
            </Space>
          </Panel>
        );
      })}
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
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Card>
            <Space direction="vertical" size="middle" style={{ width: '100%' }}>
              <Space wrap>
                <Tag color={outlineGovernance.locked ? 'red' : 'green'}>
                  {outlineGovernance.locked ? '大纲已锁定' : '大纲可编辑'}
                </Tag>
                <Tag color={outlineGovernance.approvalStatus === 'approved' ? 'success' : 'warning'}>
                  {outlineGovernance.approvalStatus || 'pending_review'}
                </Tag>
                {outlineGovernance.updatedAt && <Tag>{outlineGovernance.updatedAt}</Tag>}
              </Space>
              <Space wrap>
                {outlineGovernance.locked ? (
                  <Popconfirm
                    title="解锁大纲？"
                    description="解锁后可继续编辑完整大纲 JSON。"
                    okText="解锁"
                    cancelText="取消"
                    onConfirm={() => runOutlineGovernance('unlock')}
                  >
                    <Button icon={<UnlockOutlined />} loading={outlineGovernanceLoading}>
                      解锁大纲
                    </Button>
                  </Popconfirm>
                ) : (
                  <Button icon={<LockOutlined />} loading={outlineGovernanceLoading} onClick={() => runOutlineGovernance('lock')}>
                    锁定大纲
                  </Button>
                )}
                <Button icon={<CheckCircleOutlined />} loading={outlineGovernanceLoading} onClick={() => runOutlineGovernance('approve')}>
                  批准并锁定大纲
                </Button>
                <Button icon={<HistoryOutlined />} loading={outlineGovernanceLoading} onClick={openOutlineVersions}>
                  大纲版本
                </Button>
              </Space>
            </Space>
          </Card>
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
        </Space>
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
              expandedRowRender: (record: any, index: number) => renderChapterList(record.volumeNumber, record.chapters || [], index),
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
              disabled={!outline || !!outlineGovernance.locked}
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
            disabled={!!outlineGovernance.locked}
            placeholder={outlineGovernance.locked ? '大纲已锁定，请先解锁' : '完整大纲 JSON'}
          />
        </Space>
      </Modal>

      <Modal
        title="大纲历史版本"
        open={outlineVersionOpen}
        footer={null}
        width={860}
        onCancel={() => setOutlineVersionOpen(false)}
      >
        <Table
          dataSource={outlineVersions}
          rowKey="id"
          pagination={{ pageSize: 6 }}
          onRow={(record: any) => ({
            onClick: () => previewOutlineVersion(record),
          })}
          columns={[
            { title: '版本ID', dataIndex: 'id', key: 'id', ellipsis: true },
            { title: '书名', dataIndex: 'bookTitle', key: 'bookTitle', ellipsis: true },
            { title: '原因', dataIndex: 'archiveReason', key: 'archiveReason', width: 160 },
            { title: '归档时间', dataIndex: 'archivedAt', key: 'archivedAt', width: 190 },
            { title: '章节数', dataIndex: 'totalChapters', key: 'totalChapters', width: 90 },
          ]}
          locale={{ emptyText: '暂无大纲历史版本' }}
        />
        {outlineVersionPreview && (
          <Card size="small" title={outlineVersionPreview.id} style={{ marginTop: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="Path">{outlineVersionPreview.path}</Descriptions.Item>
                <Descriptions.Item label="Archived">{outlineVersionPreview.archivedAt}</Descriptions.Item>
                <Descriptions.Item label="Reason">{outlineVersionPreview.archiveReason || '-'}</Descriptions.Item>
              </Descriptions>
              <Button
                danger
                loading={outlineGovernanceLoading}
                onClick={() => restoreOutlineVersion(outlineVersionPreview, !!outlineGovernance.locked)}
              >
                Restore this version
              </Button>
              <ParagraphText content={JSON.stringify(outlineVersionPreview.outline || {}, null, 2)} />
            </Space>
          </Card>
        )}
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
          onRow={(record: any) => ({
            onClick: () => previewSoulVersion(record),
          })}
          columns={[
            { title: '版本ID', dataIndex: 'id', key: 'id', ellipsis: true },
            { title: '路径', dataIndex: 'path', key: 'path', ellipsis: true },
            { title: '归档时间', dataIndex: 'archivedAt', key: 'archivedAt', width: 190 },
            { title: '大小', dataIndex: 'sizeBytes', key: 'sizeBytes', width: 100 },
          ]}
          locale={{ emptyText: '暂无Project Soul历史版本' }}
        />
        {soulVersionPreview && (
          <Card size="small" title={soulVersionPreview.id} style={{ marginTop: 16 }}>
            <Space direction="vertical" style={{ width: '100%' }}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label="Path">{soulVersionPreview.path}</Descriptions.Item>
                <Descriptions.Item label="Archived">{soulVersionPreview.archivedAt}</Descriptions.Item>
              </Descriptions>
              <Button
                danger
                loading={soulGovernanceLoading}
                onClick={() => restoreSoulVersion(soulVersionPreview, !!soulGovernance.locked)}
              >
                Restore this version
              </Button>
              <ParagraphText content={soulVersionPreview.content || ''} />
            </Space>
          </Card>
        )}
      </Modal>

      <Drawer
        title={editingChapterRef ? `第${editingChapterRef.chapterNumber}章结构化编辑` : '章节结构化编辑'}
        open={chapterBoundaryOpen}
        width={760}
        onClose={() => {
          setChapterBoundaryOpen(false);
          setEditingChapterRef(null);
          chapterBoundaryForm.resetFields();
        }}
        extra={
          <Space>
            <Button onClick={() => setChapterBoundaryOpen(false)}>
              取消
            </Button>
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={savingChapterBoundary}
              disabled={!!outlineGovernance.locked}
              onClick={handleSaveChapterBoundary}
            >
              保存
            </Button>
          </Space>
        }
      >
        <Form form={chapterBoundaryForm} layout="vertical">
          <Descriptions column={2} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="卷号">{editingChapterRef?.volumeNumber || '-'}</Descriptions.Item>
            <Descriptions.Item label="章号">{editingChapterRef?.chapterNumber || '-'}</Descriptions.Item>
          </Descriptions>
          <Form.Item
            name="chapterTitle"
            label="章节标题"
            rules={[{ required: true, message: '请输入章节标题' }]}
          >
            <Input disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="plotGoal"
            label="剧情目标"
            rules={[{ required: true, message: '请输入剧情目标' }]}
          >
            <TextArea rows={3} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="targetWordCount"
            label="目标字数"
            rules={[{ required: true, message: '请输入目标字数' }]}
          >
            <InputNumber min={500} max={50000} style={{ width: '100%' }} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="coreGoal"
            label="本章核心目标"
            rules={[{ required: true, message: '请输入本章核心目标' }]}
          >
            <TextArea rows={2} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="mustWrite"
            label="必须写"
            rules={[{ required: true, message: '请输入必须写内容' }]}
          >
            <TextArea rows={4} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="allowedProgress"
            label="可铺垫但不完成"
            rules={[{ required: true, message: '请输入可铺垫内容' }]}
          >
            <TextArea rows={3} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="mustNotWrite"
            label="禁止提前写"
            rules={[{ required: true, message: '请输入禁止提前写内容' }]}
          >
            <TextArea rows={3} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="reservedForFuture"
            label="后续章纲保护"
            rules={[{ required: true, message: '请输入后续章纲保护内容' }]}
          >
            <TextArea rows={4} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="stopPoint"
            label="停止点"
            rules={[{ required: true, message: '请输入停止点' }]}
          >
            <TextArea rows={2} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item
            name="endingHook"
            label="章末钩子"
            rules={[{ required: true, message: '请输入章末钩子' }]}
          >
            <TextArea rows={2} disabled={!!outlineGovernance.locked} />
          </Form.Item>
          <Form.Item name="outlineReviewStatus" label="章纲确认状态">
            <Select
              disabled={!!outlineGovernance.locked}
              options={[
                { label: '待确认', value: 'pending_review' },
                { label: '已确认', value: 'approved' },
                { label: '需修改', value: 'needs_revision' },
              ]}
            />
          </Form.Item>
          <Form.Item name="outlineReviewNote" label="确认备注">
            <TextArea rows={3} disabled={!!outlineGovernance.locked} />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default OutlineEditor;
