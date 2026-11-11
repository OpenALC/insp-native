import { common } from '@kit.AbilityKit';
import { display, window } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { promptAction } from '@kit.ArkUI';
import { NativeStore, NativeNote, NoteGroup, MindBranch, newId, newNote } from '../data/NativeStore';
import { RichDocument, RichMark, markdownRich, readRich, richSegments } from '../data/RichDocument';

interface MindDocument {
  type: string;
  root: MindBranch;
}

interface BranchRow {
  id: string;
  text: string;
  depth: number;
}

interface TreePoint {
  id: string;
  text: string;
  parentId: string;
  depth: number;
  x: number;
  y: number;
}

@Entry
@Component
struct Index {
  private store: NativeStore | null = null;
  @State private groups: NoteGroup[] = [];
  @State private notes: NativeNote[] = [];
  @State private groupId: string = 'all';
  @State private activeId: string = '';
  @State private title: string = '';
  @State private body: string = '';
  @State private editingMode: boolean = false;
  @State private treeMode: boolean = false;
  @State private selectedBranchId: string = '';
  @State private rows: BranchRow[] = [];
  @State private treeNodes: TreePoint[] = [];
  @State private treeLines: string = '';
  @State private treeWidth: number = 220;
  @State private treeHeight: number = 160;
  @State private editingTreeId: string = '';
  @State private editingTreeText: string = '';
  @State private branchOptionsId: string = '';
  @State private dark: boolean = false;
  @State private listLayout: boolean = false;
  @State private selectionMode: boolean = false;
  @State private selectedIds: string[] = [];
  @State private batchMoveOpen: boolean = false;
  @State private batchDeleteOpen: boolean = false;
  @State private settingsAbout: boolean = false;
  @State private themeChoiceOpen: boolean = false;
  @State private layoutChoiceOpen: boolean = false;
  @State private tablet: boolean = false;
  @State private wideTablet: boolean = false;
  @State private drawer: boolean = false;
  @State private settings: boolean = false;
  @State private privateOpen: boolean = false;
  @State private addingGroup: boolean = false;
  @State private groupName: string = '';
  @State private search: string = '';
  @State private noteOptions: boolean = false;
  @State private homeOptions: boolean = false;
  @State private richActive: string[] = [];
  @State private movingGroup: boolean = false;
  private saveTimer: number = -1;
  private richController: RichEditorController = new RichEditorController();
  private richOptions: RichEditorOptions = { controller: this.richController };
  private richReady: boolean = false;
  private richLoading: boolean = false;
  private listScroller: Scroller = new Scroller();
  private sidebarWidth: number = 220;
  private suppressSelectionClick: string = '';

  aboutToAppear(): void {
    const screen = display.getDefaultDisplaySync();
    const width = screen.width / screen.densityPixels;
    this.tablet = width >= 700;
    this.wideTablet = width >= 1120;
    this.sidebarWidth = this.tablet ? 224 : width * .56667;
    NativeStore.open(getContext(this) as common.UIAbilityContext).then((store: NativeStore): void => {
      this.store = store;
      this.dark = store.dark;
      this.listLayout = store.listLayout;
      this.syncSystemBars();
      this.refresh();
    });
  }

  private async syncSystemBars(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    const current = await window.getLastWindow(context);
    current.setWindowBackgroundColor(this.dark ? '#171B16' : '#F5F7F2');
    await current.setWindowSystemBarProperties({
      statusBarColor: this.dark ? '#171B16' : '#F5F7F2',
      navigationBarColor: this.dark ? '#171B16' : '#F5F7F2',
      statusBarContentColor: this.dark ? '#D8DED4' : '#141914',
      navigationBarContentColor: this.dark ? '#D8DED4' : '#141914',
      enableStatusBarAnimation: true,
      enableNavigationBarAnimation: true
    });
  }

  private openLink(link: string): void {
    (getContext(this) as common.UIAbilityContext).openLink(link);
  }

  aboutToDisappear(): void {
    this.saveNow();
    this.store?.flush().then((): void => { this.store?.close(); });
  }

  onPageHide(): void {
    if (this.privateOpen) this.leavePrivate();
    else this.saveNow();
  }

  onBackPress(): boolean {
    if (this.addingGroup) {
      this.addingGroup = false;
      this.groupName = '';
    } else if (this.themeChoiceOpen || this.layoutChoiceOpen) {
      this.themeChoiceOpen = false;
      this.layoutChoiceOpen = false;
    } else if (this.batchMoveOpen || this.batchDeleteOpen) {
      this.batchMoveOpen = false; this.batchDeleteOpen = false;
    } else if (this.branchOptionsId !== '') {
      this.branchOptionsId = '';
    } else if (this.noteOptions || this.homeOptions) {
      this.noteOptions = false;
      this.homeOptions = false;
    } else if (this.settingsAbout) {
      this.settingsAbout = false;
    } else if (this.settings) {
      this.settings = false;
    } else if (this.selectionMode) {
      this.clearSelection();
    } else if (this.activeId !== '' && this.editingMode) {
      this.finishEditing();
    } else if (this.activeId !== '') {
      this.leaveEditor();
    } else if (this.drawer) {
      this.drawer = false;
    } else if (this.privateOpen) {
      this.leavePrivate();
    } else {
      return false;
    }
    return true;
  }

  private refresh(): void {
    if (this.store === null) return;
    this.groups = [...this.store.groups];
    this.notes = [...(this.privateOpen ? this.store.privateNotes : this.store.notes)]
      .sort((a: NativeNote, b: NativeNote): number => b.updatedAt - a.updatedAt);
  }

  private visibleNotes(): NativeNote[] {
    return this.notes.filter((note: NativeNote): boolean => {
      const groupMatches = this.groupId === 'all' || (this.groupId === 'ungrouped' ? note.groupId === null : note.groupId === this.groupId);
      const content = note.kind === 'mindmap' ? this.mindText((JSON.parse(note.body) as MindDocument).root) : readRich(note.body).text;
      const wordsMatch = this.search === '' || (note.title + ' ' + content).toLowerCase().includes(this.search.toLowerCase());
      return groupMatches && wordsMatch;
    });
  }

  private activeNote(): NativeNote | null {
    return this.notes.find((note: NativeNote): boolean => note.id === this.activeId) ?? null;
  }

  private createNote(kind: string): void {
    if (this.store === null) return;
    const note = newNote(kind, this.groupId === 'all' || this.groupId === 'ungrouped' ? null : this.groupId, this.privateOpen);
    if (this.privateOpen) this.store.privateNotes.unshift(note);
    else this.store.notes.unshift(note);
    this.storeSave();
    this.refresh();
    this.search = '';
    this.listScroller.scrollEdge(Edge.Top);
    this.open(note);
    this.editingMode = true;
  }

  private clearSelection(): void {
    this.selectionMode = false;
    this.selectedIds = [];
    this.batchMoveOpen = false;
    this.batchDeleteOpen = false;
    this.suppressSelectionClick = '';
  }

  private toggleSelection(id: string): void {
    const selected = this.selectedIds.includes(id)
      ? this.selectedIds.filter((item: string): boolean => item !== id)
      : [...this.selectedIds, id];
    if (selected.length === 0) this.clearSelection();
    else { this.selectionMode = true; this.selectedIds = selected; }
  }

  private moveSelected(groupId: string | null): void {
    const store = this.store;
    if (store === null) return;
    const notes = this.privateOpen ? store.privateNotes : store.notes;
    notes.forEach((note: NativeNote): void => {
      if (this.selectedIds.includes(note.id)) note.groupId = groupId;
    });
    this.storeSave(); this.clearSelection(); this.refresh();
  }

  private deleteSelected(): void {
    const store = this.store;
    if (store === null) return;
    if (this.privateOpen) store.privateNotes = store.privateNotes.filter((note: NativeNote): boolean => !this.selectedIds.includes(note.id));
    else store.notes = store.notes.filter((note: NativeNote): boolean => !this.selectedIds.includes(note.id));
    this.storeSave(); this.clearSelection(); this.refresh();
  }

  private open(note: NativeNote): void {
    this.saveNow();
    this.editingMode = false;
    this.activeId = note.id;
    this.title = note.title;
    this.body = note.body;
    this.treeMode = false;
    this.selectedBranchId = note.kind === 'mindmap' ? (JSON.parse(note.body) as MindDocument).root.id : '';
    this.noteOptions = false;
    this.movingGroup = false;
    this.refreshBranches();
    this.drawer = false;
    this.settings = false;
    if (note.kind === 'richtext') {
      const id = note.id;
      setTimeout((): void => { if (this.activeId === id) this.loadRich(); }, 0);
    }
  }

  private leaveEditor(): void {
    this.saveNow();
    this.activeId = '';
    this.editingMode = false;
    this.rows = [];
    this.noteOptions = false;
    this.movingGroup = false;
    this.refresh();
  }

  private finishEditing(): void {
    focusControl.requestFocus('editorDone');
    this.saveNow();
    this.editingMode = false;
  }

  private scheduleSave(): void {
    if (this.saveTimer !== -1) clearTimeout(this.saveTimer);
    this.saveTimer = setTimeout((): void => { this.saveTimer = -1; this.saveNow(); }, 500);
  }

  private saveNow(): void {
    if (this.saveTimer !== -1) {
      clearTimeout(this.saveTimer);
      this.saveTimer = -1;
    }
    const note = this.activeNote();
    if (note === null) return;
    if (note.kind === 'richtext' && this.richReady) this.body = this.snapshotRich();
    note.title = this.title.trim() || (note.kind === 'mindmap' ? '未命名的灵感笔记' : '未命名笔记');
    note.body = this.body;
    note.updatedAt = Date.now();
    this.storeSave();
  }

  private storeSave(): void {
    if (this.privateOpen) this.store?.savePrivate();
    else this.store?.saveNormal();
  }

  private loadRich(): void {
    if (!this.richReady || this.activeNote()?.kind !== 'richtext') return;
    const document = readRich(this.body);
    this.richLoading = true;
    this.richController.deleteSpans();
    const points = richSegments(document);
    for (let index = 0; index < points.length - 1; index++) {
      const start = points[index];
      const end = points[index + 1];
      const active = document.spans.filter((mark: RichMark): boolean => mark.start <= start && mark.end >= end);
      const bold = active.some((mark: RichMark): boolean => mark.type === 'bold');
      const italic = active.some((mark: RichMark): boolean => mark.type === 'italic');
      const underline = active.some((mark: RichMark): boolean => mark.type === 'underline');
      const strike = active.some((mark: RichMark): boolean => mark.type === 'strike');
      const heading = active.some((mark: RichMark): boolean => mark.type === 'heading');
      const highlight = active.some((mark: RichMark): boolean => mark.type === 'highlight');
      const quote = active.some((mark: RichMark): boolean => mark.type === 'quote');
      this.richController.addTextSpan(document.text.slice(start, end), {
        style: {
          fontColor: quote ? (this.dark ? '#A4AFA1' : '#777E77') : (this.dark ? '#D8DED4' : '#141914'),
          fontSize: heading ? 24 : (quote ? 16 : 18),
          fontWeight: bold ? FontWeight.Bold : FontWeight.Normal,
          fontStyle: italic ? FontStyle.Italic : FontStyle.Normal,
          decoration: { type: underline ? TextDecorationType.Underline : (strike ? TextDecorationType.LineThrough : TextDecorationType.None) },
          textBackgroundStyle: { color: highlight ? '#6654D81B' : '#00000000', radius: 3 }
        }
      });
    }
    this.richLoading = false;
  }

  private snapshotRich(): string {
    let text = '';
    const marks: RichMark[] = [];
    this.richController.getSpans().forEach((part: RichEditorTextSpanResult | RichEditorImageSpanResult): void => {
      if (typeof (part as RichEditorTextSpanResult).textStyle === 'undefined') return;
      const span = part as RichEditorTextSpanResult;
      const start = text.length;
      text += span.value;
      const end = text.length;
      const style = span.textStyle;
      if (style.fontWeight >= 6 && style.fontWeight <= 9 || style.fontWeight === 11) marks.push({ type: 'bold', start: start, end: end });
      if (style.fontStyle === FontStyle.Italic) marks.push({ type: 'italic', start: start, end: end });
      if (style.decoration?.type === TextDecorationType.Underline) marks.push({ type: 'underline', start: start, end: end });
      if (style.decoration?.type === TextDecorationType.LineThrough) marks.push({ type: 'strike', start: start, end: end });
      if (style.fontSize >= 22) marks.push({ type: 'heading', start: start, end: end, scale: 1.35 });
      if (style.fontSize <= 16) marks.push({ type: 'quote', start: start, end: end });
      if (this.highlighted(style.textBackgroundStyle?.color)) marks.push({ type: 'highlight', start: start, end: end });
    });
    const document: RichDocument = { version: 1, text: text, spans: marks };
    return JSON.stringify(document);
  }

  private highlighted(color: ResourceColor | undefined): boolean {
    return color === '#6654D81B' || color === 0x6654D81B;
  }

  private changedRich(): void {
    if (this.richLoading) return;
    this.scheduleSave();
    this.refreshRichActive();
  }

  private refreshRichActive(): void {
    if (!this.richReady) return;
    const style = this.richController.getTypingStyle();
    const active: string[] = [];
    if (style.fontWeight === FontWeight.Bold) active.push('bold');
    if (style.fontStyle === FontStyle.Italic) active.push('italic');
    if (style.fontSize === 24) active.push('heading');
    if (style.fontSize === 16) active.push('quote');
    if (style.decoration?.type === TextDecorationType.Underline) active.push('underline');
    if (style.decoration?.type === TextDecorationType.LineThrough) active.push('strike');
    if (this.highlighted(style.textBackgroundStyle?.color)) active.push('highlight');
    this.richActive = active;
  }

  private toggleQuoteMarker(offset: number): void {
    const content = this.richController.getSpans().map((part: RichEditorTextSpanResult | RichEditorImageSpanResult): string =>
      typeof (part as RichEditorTextSpanResult).value === 'string' ? (part as RichEditorTextSpanResult).value : '').join('');
    const start = content.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
    const quoted = content.slice(start).startsWith('❝ ');
    if (quoted) {
      this.richController.deleteSpans({ start: start, end: start + 2 });
      this.richController.setCaretOffset(Math.max(start, offset - 2));
    } else {
      this.richController.addTextSpan('❝ ', { offset: start,
        style: { fontSize: 16, fontColor: this.dark ? '#A4AFA1' : '#777E77' } });
      this.richController.setCaretOffset(offset + 2);
    }
    const style = this.richController.getTypingStyle();
    style.fontSize = quoted ? 18 : 16;
    style.fontColor = quoted ? (this.dark ? '#D8DED4' : '#141914') : (this.dark ? '#A4AFA1' : '#777E77');
    this.richController.setTypingStyle(style);
    this.changedRich();
  }

  private formatRich(kind: string): void {
    const selection = this.richController.getSelection().selection;
    if (kind === 'quote' && selection[0] === selection[1]) {
      this.toggleQuoteMarker(selection[0]);
      return;
    }
    if (selection[0] === selection[1]) {
      const style = this.richController.getTypingStyle();
      const next: RichEditorTextStyle = style;
      if (kind === 'bold') {
        next.fontWeight = style.fontWeight === FontWeight.Bold ? FontWeight.Normal : FontWeight.Bold;
      } else if (kind === 'italic') {
        next.fontStyle = style.fontStyle === FontStyle.Italic ? FontStyle.Normal : FontStyle.Italic;
      } else if (kind === 'heading') {
        next.fontSize = style.fontSize === 24 ? 18 : 24;
      } else if (kind === 'quote') {
        const quoted = style.fontSize === 16;
        next.fontSize = quoted ? 18 : 16;
        next.fontColor = quoted ? (this.dark ? '#D8DED4' : '#141914') : (this.dark ? '#A4AFA1' : '#777E77');
      } else if (kind === 'highlight') {
        next.textBackgroundStyle = {
          color: this.highlighted(style.textBackgroundStyle?.color) ? '#00000000' : '#6654D81B', radius: 3
        };
      } else {
        const decoration = kind === 'underline' ? TextDecorationType.Underline : TextDecorationType.LineThrough;
        next.decoration = { type: style.decoration?.type === decoration ? TextDecorationType.None : decoration };
      }
      this.richController.setTypingStyle(next);
      this.refreshRichActive();
      return;
    }
    const spans = this.richController.getSpans({ start: selection[0], end: selection[1] });
    const first = spans[0] as RichEditorTextSpanResult;
    const style = first.textStyle;
    if (kind === 'bold') {
      const enabled = style.fontWeight >= 6 && style.fontWeight <= 9 || style.fontWeight === 11;
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { fontWeight: enabled ? FontWeight.Normal : FontWeight.Bold } });
    } else if (kind === 'italic') {
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { fontStyle: style.fontStyle === FontStyle.Italic ? FontStyle.Normal : FontStyle.Italic } });
    } else if (kind === 'heading') {
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { fontSize: style.fontSize >= 22 ? 18 : 24, fontWeight: FontWeight.Bold } });
    } else if (kind === 'quote') {
      const quoted = style.fontSize <= 16;
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { fontSize: quoted ? 18 : 16,
          fontColor: quoted ? (this.dark ? '#D8DED4' : '#141914') : (this.dark ? '#A4AFA1' : '#777E77') } });
    } else if (kind === 'highlight') {
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { textBackgroundStyle: {
          color: this.highlighted(style.textBackgroundStyle?.color) ? '#00000000' : '#6654D81B', radius: 3
        } } });
    } else {
      const type = kind === 'underline' ? TextDecorationType.Underline : TextDecorationType.LineThrough;
      this.richController.updateSpanStyle({ start: selection[0], end: selection[1],
        textStyle: { decoration: { type: style.decoration?.type === type ? TextDecorationType.None : type } } });
    }
    this.changedRich();
    this.refreshRichActive();
  }

  private async enterPrivate(): Promise<void> {
    this.saveNow();
    if (this.store === null || !await this.store.unlockPrivate()) return;
    this.privateOpen = true;
    this.groupId = 'all';
    this.activeId = '';
    this.drawer = false;
    this.refresh();
  }

  private leavePrivate(): void {
    this.saveNow();
    this.privateOpen = false;
    this.activeId = '';
    this.groupId = 'all';
    this.store?.lockPrivate();
    this.refresh();
  }

  private addGroup(): void {
    const name = this.groupName.trim();
    if (name === '' || this.store === null) return;
    const group: NoteGroup = { id: newId(), name: name };
    this.store.groups.push(group);
    this.store.saveNormal();
    this.groupName = '';
    this.addingGroup = false;
    this.refresh();
    this.groupId = group.id;
  }

  private moveCurrent(groupId: string | null): void {
    const note = this.activeNote();
    if (note === null) return;
    note.groupId = groupId;
    note.updatedAt = Date.now();
    this.storeSave();
    this.movingGroup = false;
    this.noteOptions = false;
    this.refresh();
  }

  private deleteCurrent(): void {
    const note = this.activeNote();
    if (note === null || this.store === null) return;
    if (this.privateOpen) this.store.privateNotes = this.store.privateNotes.filter((item: NativeNote): boolean => item.id !== note.id);
    else this.store.notes = this.store.notes.filter((item: NativeNote): boolean => item.id !== note.id);
    this.activeId = '';
    this.rows = [];
    this.noteOptions = false;
    this.storeSave();
    this.refresh();
  }

  private async changePrivacy(): Promise<void> {
    this.saveNow();
    const note = this.activeNote();
    const store = this.store;
    if (note === null || store === null) return;
    if (this.privateOpen) {
      store.privateNotes = store.privateNotes.filter((item: NativeNote): boolean => item.id !== note.id);
      note.private = false;
      store.notes.unshift(note);
      await store.saveNormal();
      await store.savePrivate();
    } else {
      if (!await store.unlockPrivate()) return;
      store.notes = store.notes.filter((item: NativeNote): boolean => item.id !== note.id);
      note.private = true;
      store.privateNotes.unshift(note);
      await store.savePrivate();
      await store.saveNormal();
      store.lockPrivate();
    }
    this.activeId = '';
    this.rows = [];
    this.noteOptions = false;
    this.refresh();
  }

  private async importTextFile(): Promise<void> {
    if (this.store === null) return;
    const context = getContext(this) as common.UIAbilityContext;
    const options = new picker.DocumentSelectOptions();
    options.maxSelectNumber = 1;
    options.fileSuffixFilters = ['Text|.txt, .md, .markdown'];
    const uris = await new picker.DocumentViewPicker(context).select(options);
    if (uris.length === 0) return;
    const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
    const bytes: number[] = [];
    try {
      let count = 0;
      do {
        const buffer = new ArrayBuffer(8192);
        count = fileIo.readSync(file.fd, buffer);
        const chunk = new Uint8Array(buffer);
        for (let index = 0; index < count; index++) bytes.push(chunk[index]);
      } while (count > 0 && bytes.length <= 10 * 1024 * 1024);
    } finally {
      fileIo.closeSync(file);
    }
    if (bytes.length > 10 * 1024 * 1024) {
      promptAction.showToast({ message: '文件超过 10 MB' });
      return;
    }
    const content = util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(bytes));
    const filename = decodeURIComponent(uris[0].split('/').pop() ?? '导入笔记.txt').split('?')[0];
    const note = newNote('richtext', null, false);
    note.title = filename.replace(/\.(txt|md|markdown)$/i, '') || '导入笔记';
    note.body = filename.toLowerCase().endsWith('.txt')
      ? JSON.stringify({ version: 1, text: content, spans: [] }) : JSON.stringify(markdownRich(content));
    this.store.notes.unshift(note);
    this.store.saveNormal();
    this.settings = false;
    this.refresh();
    this.open(note);
  }

  private async exportTextFile(): Promise<void> {
    this.saveNow();
    const note = this.activeNote();
    if (note === null) return;
    const context = getContext(this) as common.UIAbilityContext;
    const options = new picker.DocumentSaveOptions();
    options.newFileNames = [note.title.replace(/[\\/:*?"<>|]/g, '_') + '.txt'];
    const uris = await new picker.DocumentViewPicker(context).save(options);
    if (uris.length === 0) return;
    const content = note.kind === 'mindmap' ? this.mindText((JSON.parse(note.body) as MindDocument).root)
      : readRich(note.body).text;
    const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.TRUNC);
    try {
      fileIo.writeSync(file.fd, content);
    } finally {
      fileIo.closeSync(file);
    }
    this.noteOptions = false;
    promptAction.showToast({ message: '已导出纯文本' });
  }

  private mindText(root: MindBranch): string {
    const lines: string[] = [root.text];
    const visit = (node: MindBranch, depth: number): void => {
      lines.push('  '.repeat(depth) + '- ' + node.text);
      node.children.forEach((child: MindBranch): void => visit(child, depth + 1));
    };
    root.children.forEach((child: MindBranch): void => visit(child, 0));
    return lines.join('\n');
  }

  private mindRoot(): MindBranch {
    return (JSON.parse(this.body) as MindDocument).root;
  }

  private refreshBranches(): void {
    const note = this.activeNote();
    if (note === null || note.kind !== 'mindmap') {
      this.rows = [];
      this.treeNodes = [];
      return;
    }
    const rows: BranchRow[] = [];
    const visit = (node: MindBranch, depth: number): void => {
      if (depth > 0) rows.push({ id: node.id, text: node.text, depth: depth });
      node.children.forEach((child: MindBranch): void => visit(child, depth + 1));
    };
    const root = this.mindRoot();
    visit(root, 0);
    this.rows = rows;
    const points: TreePoint[] = [];
    let leaf = 0;
    let maxDepth = 0;
    const place = (node: MindBranch, depth: number, parentId: string): number => {
      const childYs = node.children.map((child: MindBranch): number => place(child, depth + 1, node.id));
      const y = childYs.length === 0 ? 24 + leaf++ * 82 : (childYs[0] + childYs[childYs.length - 1]) / 2;
      maxDepth = Math.max(maxDepth, depth);
      points.push({ id: node.id, text: node.text, parentId: parentId, depth: depth, x: 24 + depth * 260, y: y });
      return y;
    };
    place(root, 0, '');
    this.treeNodes = points;
    this.treeWidth = 24 + (maxDepth + 1) * 260;
    this.treeHeight = Math.max(160, 82 * Math.max(leaf, 1) + 40);
    const byId = new Map<string, TreePoint>();
    points.forEach((point: TreePoint): void => { byId.set(point.id, point); });
    this.treeLines = points.filter((point: TreePoint): boolean => point.parentId !== '').map((point: TreePoint): string => {
      const parent = byId.get(point.parentId) as TreePoint;
      const x1 = vp2px(parent.x + 210);
      const y1 = vp2px(parent.y + 26);
      const x2 = vp2px(point.x);
      const y2 = vp2px(point.y + 26);
      return `M${x1} ${y1} C${x1 + vp2px(24)} ${y1} ${x2 - vp2px(24)} ${y2} ${x2} ${y2}`;
    }).join(' ');
  }

  private findBranch(root: MindBranch, id: string): MindBranch | null {
    if (root.id === id) return root;
    for (const child of root.children) {
      const found = this.findBranch(child, id);
      if (found !== null) return found;
    }
    return null;
  }

  private findParent(root: MindBranch, id: string): MindBranch | null {
    for (const child of root.children) {
      if (child.id === id) return root;
      const found = this.findParent(child, id);
      if (found !== null) return found;
    }
    return null;
  }

  private updateMind(root: MindBranch, redraw: boolean = true): void {
    root.text = this.title;
    this.body = JSON.stringify({ type: 'mindmap', root: root });
    if (redraw) this.refreshBranches();
    this.scheduleSave();
  }

  private addBranch(parentId: string = ''): void {
    const root = this.mindRoot();
    const parent = parentId === '' ? root : this.findBranch(root, parentId);
    if (parent === null) return;
    const id = newId();
    parent.children.push({ id: id, text: '新分支', children: [] });
    this.selectedBranchId = id;
    this.editingMode = true;
    this.updateMind(root);
    if (!this.treeMode) {
      setTimeout((): void => { focusControl.requestFocus(`mind-${id}`); }, 0);
    }
  }

  private addSibling(): void {
    const root = this.mindRoot();
    const parent = this.findParent(root, this.selectedBranchId);
    if (parent === null) { this.addBranch(root.id); return; }
    const index = parent.children.findIndex((node: MindBranch): boolean => node.id === this.selectedBranchId);
    const id = newId();
    parent.children.splice(index + 1, 0, { id: id, text: '新分支', children: [] });
    this.selectedBranchId = id;
    this.updateMind(root);
  }

  private editBranch(id: string, text: string): void {
    const root = this.mindRoot();
    const branch = this.findBranch(root, id);
    if (branch === null) return;
    branch.text = text;
    const row = this.rows.find((item: BranchRow): boolean => item.id === id);
    if (row !== undefined) row.text = text;
    this.updateMind(root, false);
  }

  private commitTreeEdit(): void {
    if (this.editingTreeId === '') return;
    const id = this.editingTreeId;
    const text = this.editingTreeText.trim() || '新分支';
    this.editingTreeId = '';
    this.editBranch(id, text);
    this.refreshBranches();
  }

  private moveBranch(id: string, deeper: boolean): void {
    const root = this.mindRoot();
    const parent = this.findParent(root, id);
    if (parent === null) return;
    const index = parent.children.findIndex((node: MindBranch): boolean => node.id === id);
    if (deeper) {
      if (index === 0) return;
      const branch = parent.children.splice(index, 1)[0];
      parent.children[index - 1].children.push(branch);
    } else {
      const grand = this.findParent(root, parent.id);
      if (grand === null) return;
      const branch = parent.children.splice(index, 1)[0];
      const parentIndex = grand.children.findIndex((node: MindBranch): boolean => node.id === parent.id);
      grand.children.splice(parentIndex + 1, 0, branch);
    }
    this.updateMind(root);
  }

  private removeBranch(id: string): void {
    const root = this.mindRoot();
    const parent = this.findParent(root, id);
    if (parent === null) return;
    parent.children = parent.children.filter((node: MindBranch): boolean => node.id !== id);
    this.selectedBranchId = parent.id;
    this.updateMind(root);
  }

  private moveSibling(id: string, step: number): void {
    const root = this.mindRoot();
    const parent = this.findParent(root, id);
    if (parent === null) return;
    const index = parent.children.findIndex((node: MindBranch): boolean => node.id === id);
    const target = index + step;
    if (target < 0 || target >= parent.children.length) return;
    const branch = parent.children.splice(index, 1)[0];
    parent.children.splice(target, 0, branch);
    this.updateMind(root);
  }

  private branchAction(action: string): void {
    const id = this.branchOptionsId;
    this.branchOptionsId = '';
    if (action === 'add') this.addBranch(id);
    else if (action === 'up') this.moveSibling(id, -1);
    else if (action === 'down') this.moveSibling(id, 1);
    else if (action === 'deeper') this.moveBranch(id, true);
    else if (action === 'shallower') this.moveBranch(id, false);
    else if (action === 'delete') this.removeBranch(id);
  }

  @Builder private sidebar(): void {
    Column({ space: 4 }) {
      Column({ space: 4 }) {
        Text('拾页 · Native').fontSize(21).fontWeight(FontWeight.Bold)
          .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').textAlign(TextAlign.Start)
        Text('by OpenALC').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166')
          .width('100%').textAlign(TextAlign.End)
      }.width('100%').padding({ left: 12, right: 10, top: 12, bottom: 36 })
      Text('笔记空间').fontSize(12).fontWeight(FontWeight.Medium)
        .fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
        .padding({ left: 12, bottom: 8 })
      this.sidebarItem('全部笔记', 'all')
      this.sidebarItem('未分组', 'ungrouped')
      Row() {
        Text('分组').fontSize(12).fontWeight(FontWeight.Medium)
          .fontColor(this.dark ? '#A4AFA1' : '#677166').layoutWeight(1)
        Button() {
          Image((this.dark ? $r('app.media.nav_plus_dark') : $r('app.media.nav_plus'))).width(18).height(18)

        }.type(ButtonType.Normal).width(38).height(38).borderRadius(12)
          .backgroundColor('#00000000')
          .onClick((): void => { this.drawer = false; this.addingGroup = true; })
      }.width('100%').padding({ left: 12, top: 20, bottom: 4 })
      ForEach(this.groups, (group: NoteGroup) => {
        this.sidebarItem(group.name, group.id)
      }, (group: NoteGroup): string => group.id)
      if (this.groups.length === 0) {
        Text('分组让笔记更好找').fontSize(13).fontColor(this.dark ? '#A4AFA1' : '#677166')
          .width('100%').padding({ left: 13, top: 8 })
      }
      Blank()
      Divider().color(this.dark ? '#394537' : '#DCE8D6').margin({ bottom: 8 })
      Button() {
        Row({ space: 10 }) {
          Image((this.dark ? $r('app.media.nav_lock_dark') : $r('app.media.nav_lock'))).width(19).height(19)

          Text(this.privateOpen ? '退出隐私笔记' : '隐私笔记').fontSize(15)
            .fontColor(this.dark ? '#D8DED4' : '#141914')
        }.width('100%')
      }.type(ButtonType.Normal).width('100%').height(48)
        .backgroundColor('#00000000').borderRadius(14)
        .onClick((): void => { if (this.privateOpen) this.leavePrivate(); else this.enterPrivate(); })
      Button() {
        Row({ space: 10 }) {
          Image((this.dark ? $r('app.media.nav_settings_dark') : $r('app.media.nav_settings'))).width(19).height(19)

          Text('设置').fontSize(15).fontColor(this.dark ? '#D8DED4' : '#141914')
        }.width('100%')
      }.type(ButtonType.Normal).width('100%').height(48)
        .backgroundColor('#00000000').borderRadius(14)
        .onClick((): void => { this.settings = true; this.drawer = false; })
    }
    .padding(14).width(this.sidebarWidth).height('100%')
    .backgroundColor(this.dark ? '#20291E' : '#EFF8EA')
  }

  @Builder private sidebarItem(label: string, id: string): void {
    Row({ space: 10 }) {
      if (this.groupId === id) Column().width(3).height(28).borderRadius(2).backgroundColor('#54D81B')
      else Blank().width(3)
      if (id === 'all') Image((this.dark ? $r('app.media.nav_inbox_dark') : $r('app.media.nav_inbox'))).width(18).height(18)
      else if (id === 'ungrouped') Image((this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive'))).width(18).height(18)
      else Image((this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder'))).width(18).height(18)
      Text(label).fontSize(15).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
      Text((id === 'all' ? this.notes.length : id === 'ungrouped' ? this.notes.filter((note: NativeNote): boolean => note.groupId === null).length
        : this.notes.filter((note: NativeNote): boolean => note.groupId === id).length).toString())
        .fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166')
    }.width('100%').height(48).padding({ left: 9, right: 11 })
      .backgroundColor(this.groupId === id ? (this.dark ? '#34562C' : '#E1F6D8') : '#00000000')
      .borderRadius(14)
      .onClick((): void => { this.groupId = id; this.drawer = false; this.activeId = ''; })
  }

  @Builder private listPane(): void {
    Column() {
      if (this.tablet) {
        Text('拾页  /  NOTES').fontSize(12).fontWeight(FontWeight.Medium)
          .fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
          .padding({ left: 18, top: 20, bottom: 5 })
      }
      Row() {
        if (!this.tablet) {
          Button('☰').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => { this.drawer = true; })
        }
        Text(this.selectionMode ? `已选 ${this.selectedIds.length} 项` : this.privateOpen ? '隐私笔记' : '笔记').fontSize(25).fontWeight(FontWeight.Bold)
          .fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
        if (this.selectionMode) {
          Button(this.selectedIds.length === this.visibleNotes().length ? '取消全选' : '全选').type(ButtonType.Normal)
            .fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => {
              if (this.selectedIds.length === this.visibleNotes().length) this.clearSelection();
              else this.selectedIds = this.visibleNotes().map((note: NativeNote): string => note.id);
            })
          Button('取消').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').onClick((): void => this.clearSelection())
        } else {
          Button() {
            Image(this.listLayout ? (this.dark ? $r('app.media.nav_grid_dark') : $r('app.media.nav_grid')) : (this.dark ? $r('app.media.mind_list_dark') : $r('app.media.mind_list')))
              .width(21).height(21)
          }.type(ButtonType.Normal).width(40).height(40).borderRadius(12)
            .backgroundColor('#00000000')
            .onClick((): void => {
              this.listLayout = !this.listLayout;
              if (this.store !== null) { this.store.listLayout = this.listLayout; this.store.saveNormal(); }
            })
          Button() {
            Image((this.dark ? $r('app.media.nav_more_dark') : $r('app.media.nav_more'))).width(22).height(22)

          }.type(ButtonType.Normal).width(40).height(40).borderRadius(12)
            .backgroundColor('#00000000')
            .onClick((): void => { this.homeOptions = true; })
          if (!this.tablet) Text(this.visibleNotes().length.toString()).fontSize(15).fontColor(this.dark ? '#A4AFA1' : '#677166')
        }
      }.width('100%').padding({ left: 18, right: 18, top: this.tablet ? 0 : 18, bottom: 12 })
      if (this.tablet && !this.selectionMode) {
        Row({ space: 8 }) {
          Button() {
            Row({ space: 8 }) {
              Image($r('app.media.nav_note')).width(18).height(18)

              Text('笔记').fontSize(14).fontColor('#141914')
            }
          }.type(ButtonType.Normal).layoutWeight(1).height(40).borderRadius(12)
            .backgroundColor('#54D81B').onClick((): void => this.createNote('richtext'))
          Button() {
            Row({ space: 8 }) {
              Image((this.dark ? $r('app.media.mind_tree_dark') : $r('app.media.mind_tree'))).width(18).height(18)

              Text('灵感笔记').fontSize(14).fontColor(this.dark ? '#D8DED4' : '#141914')
            }
          }.type(ButtonType.Normal).layoutWeight(1).height(40).borderRadius(12)
            .backgroundColor(this.dark ? '#303830' : '#E7F4E1').onClick((): void => this.createNote('mindmap'))
        }.width('100%').padding({ left: 18, right: 18, bottom: 16 })
      }
      TextInput({ placeholder: '搜索笔记', text: this.search })
        .fontColor(this.dark ? '#D8DED4' : '#141914')
        .placeholderColor(this.dark ? '#A4AFA1' : '#677166')
        .height(48).borderRadius(14)
        .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
        .onChange((value: string): void => { this.search = value; })
        .margin({ left: 16, right: 16, bottom: 12 })
      if (this.tablet) {
        Row() {
          Text(`${this.visibleNotes().length} 篇笔记`).fontSize(13)
            .fontColor(this.dark ? '#A4AFA1' : '#677166').layoutWeight(1)
          Text('最近编辑').fontSize(13).fontColor(this.dark ? '#A4AFA1' : '#677166')
        }.width('100%').padding({ left: 18, right: 18, bottom: 14 })
      }
      Stack({ alignContent: Alignment.Bottom }) {
      WaterFlow({ scroller: this.listScroller }) {
        ForEach(this.visibleNotes(), (note: NativeNote) => {
          FlowItem() {
            Column({ space: 10 }) {
              Row() {
                Text(note.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(this.dark ? '#D8DED4' : '#141914')
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
                if (this.selectionMode) {
                  Text(this.selectedIds.includes(note.id) ? '✓' : '○').fontSize(21)
                    .fontColor(this.selectedIds.includes(note.id) ? '#54D81B' : (this.dark ? '#A4AFA1' : '#677166'))
                }
              }.width('100%')
              Text(this.preview(note) || '空白笔记').fontSize(13).fontColor(this.dark ? '#A4AFA1' : '#677166').maxLines(4)
                .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
              Row() {
                Text(note.kind === 'mindmap' ? '灵感笔记' : '笔记').fontSize(11)
                  .fontColor(this.dark ? '#A4AFA1' : '#677166').layoutWeight(1)
                Text(new Date(note.updatedAt).toLocaleDateString()).fontSize(11)
                  .fontColor(this.dark ? '#A4AFA1' : '#677166')
              }.width('100%').padding({ top: 3 })
            }
            .padding(17).width('100%')
            .backgroundColor(this.selectedIds.includes(note.id) || this.activeId === note.id
              ? (this.dark ? '#34562C' : '#EAF8E4') : (this.dark ? '#252B24' : '#FFFFFF'))
            .borderRadius(20)
            .onClick((): void => {
              if (this.suppressSelectionClick === note.id) { this.suppressSelectionClick = ''; return; }
              if (this.selectionMode) this.toggleSelection(note.id);
              else this.open(note);
            })
            .gesture(LongPressGesture().onAction((): void => {
              this.suppressSelectionClick = note.id;
              this.toggleSelection(note.id);
            }))
          }
        }, (note: NativeNote): string => note.id)
      }.columnsTemplate(this.tablet || this.listLayout ? '1fr' : '1fr 1fr').columnsGap(10).rowsGap(10)
        .width('100%').height('100%').padding({ left: 14, right: 14, bottom: this.tablet ? 14 : 92 })
      if (this.visibleNotes().length === 0) {
        Column({ space: 14 }) {
          Image((this.dark ? $r('app.media.nav_note_dark') : $r('app.media.nav_note'))).width(42).height(42)

          Text('这里还空着').fontSize(19).fontWeight(FontWeight.Medium)
            .fontColor(this.dark ? '#D8DED4' : '#141914')
          Text('先记下一个想法，之后再慢慢整理。').fontSize(13)
            .fontColor(this.dark ? '#A4AFA1' : '#677166').textAlign(TextAlign.Center)
          Button('新建笔记').fontColor('#141914').backgroundColor('#54D81B')
            .borderRadius(14).onClick((): void => this.createNote('richtext'))
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center)
      }
      if (this.selectionMode) {
        Row({ space: 10 }) {
          Button() {
            Row({ space: 9 }) {
              Image((this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder'))).width(21).height(21)
              Text('移动到').fontSize(15).fontColor(this.dark ? '#D8DED4' : '#141914')
            }
          }.type(ButtonType.Normal).layoutWeight(1).height(48)
            .backgroundColor(this.dark ? '#303830' : '#F0F0F0').borderRadius(17)
            .onClick((): void => { this.batchMoveOpen = true; })
          Button() {
            Row({ space: 9 }) {
              Image((this.dark ? $r('app.media.nav_trash_dark') : $r('app.media.nav_trash'))).width(21).height(21)
              Text('删除').fontSize(15).fontColor('#B84848')
            }
          }.type(ButtonType.Normal).layoutWeight(1).height(48)
            .backgroundColor(this.dark ? '#303830' : '#F0F0F0').borderRadius(17)
            .onClick((): void => { this.batchDeleteOpen = true; })
        }.padding(8).backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(24)
          .margin({ left: 22, right: 22, bottom: 16 })
      } else if (!this.tablet) {
      Row({ space: 10 }) {
        Button('＋ 笔记').width(this.tablet ? 90 : 120).fontColor('#141914').backgroundColor('#E6F8DF')
          .onClick((): void => this.createNote('richtext'))
        Divider().vertical(true).height(22).color(this.dark ? '#4B5449' : '#DDE5DA')
        Button('◇ 灵感笔记').width(this.tablet ? 120 : 150).fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
          .onClick((): void => this.createNote('mindmap'))
      }.padding(6).backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
        .borderRadius(36).alignSelf(ItemAlign.Center).margin({ bottom: 16 })
      }
      }.layoutWeight(1).width('100%')
    }.width(this.wideTablet ? 326 : '100%').height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }

  private preview(note: NativeNote): string {
    if (note.kind !== 'mindmap') return readRich(note.body).text.replace(/\s+/g, ' ').slice(0, 180);
    const root = (JSON.parse(note.body) as MindDocument).root;
    return root.children.map((node: MindBranch): string => node.text).join(' · ');
  }

  private currentGroupLabel(): string {
    const groupId = this.activeNote()?.groupId;
    return this.groups.find((group: NoteGroup): boolean => group.id === groupId)?.name || '未分组';
  }

  @Builder private editorPane(): void {
    Column() {
      Row({ space: 8 }) {
        if (!this.wideTablet) {
          Button('‹').type(ButtonType.Normal).width(42).height(42).fontSize(26)
            .fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor('#00000000')
            .onClick((): void => this.leaveEditor())
        }
        Text(`拾页  ›  ${this.currentGroupLabel()}  ›  ${this.activeNote()?.kind === 'mindmap' ? '灵感笔记' : '笔记'}`).fontSize(13)
          .fontColor(this.dark ? '#A4AFA1' : '#677166').layoutWeight(1)
        if (this.editingMode) {
          Button('✓').type(ButtonType.Normal).width(42).height(42).fontSize(22)
            .fontColor('#141914').backgroundColor('#54D81B').borderRadius(13)
            .id('editorDone').focusable(true).onClick((): void => this.finishEditing())
        }
        if (this.activeNote()?.kind === 'mindmap') {
          Button() {
            Image(this.treeMode ? (this.dark ? $r('app.media.mind_list_dark') : $r('app.media.mind_list')) : (this.dark ? $r('app.media.mind_tree_dark') : $r('app.media.mind_tree'))).width(22).height(22)

          }.type(ButtonType.Normal).width(42).height(42).borderRadius(13)
            .backgroundColor(this.dark ? '#252B24' : '#F0F5ED')
            .onClick((): void => { this.commitTreeEdit(); animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => { this.treeMode = !this.treeMode; }); })
        }
        Button() {
          Image((this.dark ? $r('app.media.nav_more_dark') : $r('app.media.nav_more'))).width(22).height(22)

        }.type(ButtonType.Normal).width(42).height(42)
          .backgroundColor('#00000000')
          .onClick((): void => { this.noteOptions = !this.noteOptions; })
      }.width(this.wideTablet ? 760 : '100%').alignSelf(ItemAlign.Center)
        .padding({ left: 18, right: 18, top: 14, bottom: 14 })
      Divider().color(this.dark ? '#394537' : '#E1E8DE')
        .width(this.wideTablet ? 760 : '94%').alignSelf(ItemAlign.Center)
      Column({ space: 12 }) {
        Text(`${new Date(this.activeNote()?.updatedAt || Date.now()).toLocaleTimeString()}  |  ${this.activeNote()?.kind === 'mindmap' ? this.rows.length : readRich(this.body).text.length} 字`)
          .fontSize(13).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
        if (this.editingMode) {
          TextInput({ text: this.title, placeholder: '笔记名称' }).id('editorTitle')
            .fontSize(31).fontWeight(FontWeight.Bold).fontColor(this.dark ? '#D8DED4' : '#141914')
            .width('100%').height(74).backgroundColor('#00000000').borderWidth(0)
            .onChange((value: string): void => {
              this.title = value;
              if (this.activeNote()?.kind === 'mindmap') this.updateMind(this.mindRoot());
              else this.scheduleSave();
            })
        } else {
          Text(this.title).fontSize(31).fontWeight(FontWeight.Bold)
            .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%')
            .onClick((): void => {
              this.editingMode = true;
              setTimeout((): void => { focusControl.requestFocus('editorTitle'); }, 0);
            })
        }
      }.width(this.wideTablet ? 760 : '100%').alignSelf(ItemAlign.Center)
        .padding({ left: 24, right: 24, top: 20, bottom: 18 })
      Divider().color(this.dark ? '#394537' : '#E1E8DE')
        .width(this.wideTablet ? 760 : '94%').alignSelf(ItemAlign.Center)
      if (this.activeNote()?.kind === 'mindmap') {
        if (this.treeMode) {
          Scroll() {
            Stack({ alignContent: Alignment.TopStart }) {
              Path({ width: this.treeWidth, height: this.treeHeight, commands: this.treeLines })
                .fill('#00000000').stroke(this.dark ? '#718E69' : '#9FC796').strokeWidth(2)
              ForEach(this.treeNodes, (node: TreePoint) => {
                if (this.editingTreeId === node.id) {
                  TextInput({ text: this.editingTreeText }).fontSize(16).width(210).height(52)
                    .position({ x: node.x, y: node.y })
                    .onChange((value: string): void => { this.editingTreeText = value; })
                    .onSubmit((): void => this.commitTreeEdit())
                    .onBlur((): void => this.commitTreeEdit())
                } else {
                  Text(node.text).fontSize(node.depth === 0 ? 18 : 16).fontWeight(node.depth === 0 ? FontWeight.Bold : FontWeight.Normal)
                    .fontColor(this.dark ? '#D8DED4' : '#141914').maxLines(2)
                    .textOverflow({ overflow: TextOverflow.Ellipsis }).width(210).height(52).padding({ left: 10, right: 32 })
                    .backgroundColor(node.depth === 0 ? (this.dark ? '#34562C' : '#E6F8DF') : (this.dark ? '#252B24' : '#FFFFFF'))
                    .borderRadius(16).position({ x: node.x, y: node.y })
                    .onClick((): void => {
                      this.selectedBranchId = node.id;
                      if (node.depth === 0) return;
                      if (!this.editingMode) { this.editingMode = true; return; }
                      this.editingTreeId = node.id; this.editingTreeText = node.text;
                    })
                }
              }, (node: TreePoint): string => node.id)
            }.width(this.treeWidth).height(this.treeHeight)
          }.scrollable(ScrollDirection.Free).layoutWeight(1).width('100%')
        } else {
          Scroll() {
            Column({ space: 8 }) {
              if (this.rows.length === 0) {
                Column({ space: 14 }) {
                  Image((this.dark ? $r('app.media.mind_tree_dark') : $r('app.media.mind_tree'))).width(42).height(42)

                  Text('从第一个想法开始').fontSize(21).fontWeight(FontWeight.Medium)
                    .fontColor(this.dark ? '#D8DED4' : '#141914')
                  Text('以笔记名称为中心主题，逐步展开你的灵感。').fontSize(14)
                    .fontColor(this.dark ? '#A4AFA1' : '#677166').textAlign(TextAlign.Center)
                  Button('＋  添加第一个分支').type(ButtonType.Normal)
                    .fontColor('#141914').backgroundColor('#54D81B').borderRadius(14)
                    .margin({ top: 6 }).onClick((): void => this.addBranch(this.mindRoot().id))
                }.width('100%').height(400).justifyContent(FlexAlign.Center)
                  .alignItems(HorizontalAlign.Center)
              }
              ForEach(this.rows, (row: BranchRow) => {
              Row({ space: 12 }) {
                Column().width(7).height(7).borderRadius(4).backgroundColor('#54D81B')
                if (this.editingMode) {
                  TextInput({ text: row.text }).id(`mind-${row.id}`).fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').width(0).layoutWeight(1)
                    .backgroundColor('#00000000')
                    .onFocus((): void => { this.selectedBranchId = row.id; })
                    .onChange((value: string): void => this.editBranch(row.id, value))
                } else {
                  Text(row.text).fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914')
                    .width(0).layoutWeight(1).onClick((): void => {
                      this.selectedBranchId = row.id; this.editingMode = true;
                      setTimeout((): void => { focusControl.requestFocus(`mind-${row.id}`); }, 0);
                    })
                }
              }.width('100%').height(54).padding({ left: 18 + (row.depth - 1) * 24, right: 12 })
                .backgroundColor(this.selectedBranchId === row.id ? (this.dark ? '#34562C' : '#EDF8E8') : (this.dark ? '#252B24' : '#F7F9F5'))
                .borderRadius(15)
              }, (row: BranchRow): string => row.id)
            }.width(this.wideTablet ? 760 : '100%').constraintSize({ minHeight: '100%' }).padding(24)
              .alignSelf(ItemAlign.Center)
              .justifyContent(FlexAlign.Start).alignItems(HorizontalAlign.Start)
          }.layoutWeight(1)
        }
        if (this.editingMode) {
          Row() {
            this.mindTool('升级', '↖', 'shallower')
            this.mindTool('降级', '↘', 'deeper')
            this.mindTool('子主题', '+', 'child')
            this.mindTool('同级', '↳', 'sibling')
            this.mindTool('更多', '···', 'more')
          }.width(this.tablet ? 520 : '94%').height(64)
            .padding({ left: 8, right: 8, top: 5, bottom: 5 })
            .backgroundColor(this.dark ? '#303830' : '#EFF4EB')
            .borderRadius(22).alignSelf(ItemAlign.Center).margin({ bottom: 14 })
        }
      } else {
        RichEditor(this.richOptions)
          .placeholder('开始记录…')
          .caretColor('#54D81B')
          .backgroundColor(this.dark ? '#171B16' : '#FFFFFF')
          .padding({ left: 30, right: 30, top: 22, bottom: 22 }).layoutWeight(1)
          .width(this.wideTablet ? 760 : '100%').alignSelf(ItemAlign.Center)
          .onReady((): void => { this.richReady = true; this.loadRich(); })
          .onFocus((): void => { this.editingMode = true; })
          .onDidChange((): void => this.changedRich())
          .onSelectionChange((): void => this.refreshRichActive())
        if (this.editingMode) {
        Scroll() {
          Row({ space: 5 }) {
            this.formatTool('H', 'heading')
            this.formatTool('B', 'bold')
            this.formatTool('I', 'italic')
            this.formatTool('U', 'underline')
            this.formatTool('S', 'strike')
            this.formatTool('M', 'highlight')
            this.formatTool('❝', 'quote')
          }
        }.scrollable(ScrollDirection.Horizontal).width(this.tablet ? 430 : '100%')
          .alignSelf(ItemAlign.Center).padding(6).borderRadius(20)
          .backgroundColor(this.dark ? '#252B24' : '#F0F4ED')
          .margin({ bottom: 14 })
        }
      }
    }.width(0).layoutWeight(1).height('100%').backgroundColor(this.dark ? '#171B16' : '#FFFFFF')
  }

  @Builder private formatTool(label: string, kind: string): void {
    Button(label).type(ButtonType.Normal).width(46).height(44).borderRadius(13)
      .fontSize(17).fontWeight(kind === 'bold' ? FontWeight.Bold : FontWeight.Normal)
      .fontColor(this.dark ? '#D8DED4' : '#141914')
      .backgroundColor(this.richActive.includes(kind) ? (this.dark ? '#34562C' : '#DDF4D5') : '#00000000')
      .onClick((): void => this.formatRich(kind))
  }

  @Builder private mindTool(label: string, symbol: string, action: string): void {
    Button() {
      Column({ space: 2 }) {
        Text(symbol).fontSize(21)
        Text(label).fontSize(12)
      }
    }.type(ButtonType.Normal).layoutWeight(1).height(52)
      .fontColor(this.dark ? '#D8DED4' : '#141914')
      .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
      .onClick((): void => {
        if (action === 'child') this.addBranch(this.selectedBranchId);
        else if (action === 'sibling') this.addSibling();
        else if (action === 'deeper') this.moveBranch(this.selectedBranchId, true);
        else if (action === 'shallower') this.moveBranch(this.selectedBranchId, false);
        else if (action === 'more' && this.selectedBranchId !== this.mindRoot().id) this.branchOptionsId = this.selectedBranchId;
      })
  }

  @Builder private optionsSheet(): void {
    Column({ space: 8 }) {
      Text(this.movingGroup ? '移动到' : '笔记选项').fontSize(23).fontWeight(FontWeight.Bold)
        .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').padding({ bottom: 12 })
      if (this.movingGroup) {
        Button('未分组').width('100%').fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16).onClick((): void => this.moveCurrent(null))
        ForEach(this.groups, (group: NoteGroup) => {
          Button(group.name).width('100%').fontColor(this.dark ? '#D8DED4' : '#141914')
            .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16).onClick((): void => this.moveCurrent(group.id))
        }, (group: NoteGroup): string => group.id)
      } else {
        this.noteOption('移动到分组', (this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder')), 'move')
        this.noteOption('导出纯文本', (this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive')), 'export')
        this.noteOption(this.privateOpen ? '移出隐私笔记' : '移入隐私笔记', (this.dark ? $r('app.media.nav_lock_dark') : $r('app.media.nav_lock')), 'privacy')
        this.noteOption('删除笔记', (this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive')), 'delete')
      }
      Button('取消').width('100%').fontColor(this.dark ? '#A4AFA1' : '#677166')
        .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
        .onClick((): void => { this.noteOptions = false; this.movingGroup = false; })
    }.width(this.tablet ? 480 : '92%').padding(20).borderRadius(28)
      .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
      .alignSelf(ItemAlign.Center).margin({ bottom: 16 })
  }

  @Builder private noteOption(label: string, icon: ResourceStr, action: string): void {
    Button() {
      Row({ space: 12 }) {
        Image(icon).width(20).height(20)
        Text(label).fontSize(17).fontColor(action === 'delete' ? '#B84848' : (this.dark ? '#D8DED4' : '#141914'))
      }.width('100%')
    }.type(ButtonType.Normal).width('100%').backgroundColor(this.dark ? '#303830' : '#F4F6F2')
      .borderRadius(16).onClick((): void => {
        if (action === 'move') this.movingGroup = true;
        else if (action === 'export') { this.noteOptions = false; this.exportTextFile(); }
        else if (action === 'privacy') { this.noteOptions = false; this.changePrivacy(); }
        else if (action === 'delete') this.deleteCurrent();
      })
  }

  @Builder private branchSheet(): void {
    Column({ space: 8 }) {
      Text('分支操作').fontSize(23).fontWeight(FontWeight.Bold)
        .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').padding({ bottom: 12 })
      this.branchSheetItem('上移', 'up')
      this.branchSheetItem('下移', 'down')
      this.branchSheetItem('删除分支', 'delete')
      Button('取消').width('100%').fontColor(this.dark ? '#A4AFA1' : '#677166')
        .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
        .onClick((): void => { this.branchOptionsId = ''; })
    }.width(this.tablet ? 480 : '92%').padding(20).borderRadius(28)
      .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
      .alignSelf(ItemAlign.Center).margin({ bottom: 16 })
  }

  @Builder private branchSheetItem(label: string, action: string): void {
    Button(label).width('100%').fontColor(action === 'delete' ? '#B84848' : (this.dark ? '#D8DED4' : '#141914'))
      .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
      .onClick((): void => this.branchAction(action))
  }

  @Builder private layoutChoice(label: string, list: boolean): void {
    Button() {
      Row({ space: 12 }) {
        Image(list ? (this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive')) : (this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder'))).width(20).height(20)
        Text(label).fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
        if (this.listLayout === list) Text('✓').fontSize(20).fontColor('#54D81B')
      }.width('100%')
    }.type(ButtonType.Normal).width('100%').backgroundColor(this.dark ? '#303830' : '#F4F6F2')
      .borderRadius(16).onClick((): void => {
        this.listLayout = list;
        this.layoutChoiceOpen = false;
        if (this.store !== null) { this.store.listLayout = list; this.store.saveNormal(); }
      })
  }

  @Builder private themeChoice(label: string, value: boolean): void {
    Button() {
      Row({ space: 12 }) {
        Image((this.dark ? $r('app.media.nav_settings_dark') : $r('app.media.nav_settings'))).width(20).height(20)
        Text(label).fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
        if (this.dark === value) Text('✓').fontSize(20).fontColor('#54D81B')
      }.width('100%')
    }.type(ButtonType.Normal).width('100%').backgroundColor(this.dark ? '#303830' : '#F4F6F2')
      .borderRadius(16).onClick((): void => {
        this.dark = value;
        this.themeChoiceOpen = false;
        this.syncSystemBars();
        if (this.store !== null) { this.store.dark = value; this.store.saveNormal(); }
      })
  }

  @Builder private aboutItem(label: string, detail: string, icon: ResourceStr, link: string): void {
    Row({ space: 12 }) {
      if (link !== '') Image(icon).width(20).height(20)
      Text(label).fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
      Text(detail).fontSize(13).fontColor(this.dark ? '#A4AFA1' : '#677166')
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (link !== '') Text('›').fontSize(19).fontColor(this.dark ? '#A4AFA1' : '#677166')
    }.width('100%').padding(18)
      .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
      .onClick((): void => { if (link !== '') this.openLink(link); })
  }

  @Builder private settingsPane(): void {
    Column({ space: 12 }) {
      Row() {
        Button('‹').type(ButtonType.Normal).width(42).height(42).borderRadius(13)
          .fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
          .onClick((): void => { if (this.settingsAbout) this.settingsAbout = false; else this.settings = false; })
        Text(this.settingsAbout ? '关于拾页' : '设置').fontSize(25).fontWeight(FontWeight.Bold)
          .fontColor(this.dark ? '#D8DED4' : '#141914')
      }.width(this.tablet ? 700 : '100%').alignSelf(ItemAlign.Center)
      Scroll() {
        Column({ space: 12 }) {
          if (this.settingsAbout) {
            Column({ space: 7 }) {
              Text('拾页 · Native').fontSize(25).fontWeight(FontWeight.Bold).width('100%').textAlign(TextAlign.Center)
                .fontColor(this.dark ? '#D8DED4' : '#141914')
              Text('给想法，留一页空白。').fontSize(15).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%').textAlign(TextAlign.Center)
              Text('by OpenALC').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%').textAlign(TextAlign.Center)
            }.width('100%').alignItems(HorizontalAlign.Center).padding({ top: 24, bottom: 30 })
            Text('应用信息').fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            this.aboutItem('当前版本', 'v0.1.0', (this.dark ? $r('app.media.nav_inbox_dark') : $r('app.media.nav_inbox')), '')
            this.aboutItem('获取更新', '', (this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive')), 'https://github.com/OpenALC/insp-native/releases')
            this.aboutItem('GitHub 仓库', 'OpenALC/insp-native', (this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder')), 'https://github.com/OpenALC/insp-native')
            this.aboutItem('开源协议', 'MIT', (this.dark ? $r('app.media.nav_lock_dark') : $r('app.media.nav_lock')), 'https://github.com/OpenALC/insp-native/blob/main/LICENSE')
          } else {
            Text('外观').fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            Row() {
              Image((this.dark ? $r('app.media.nav_settings_dark') : $r('app.media.nav_settings'))).width(20).height(20)
              Text('外观').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text(this.dark ? '深色' : '浅色').fontSize(15).fontColor(this.dark ? '#A4AFA1' : '#677166')
              Text('›').fontSize(19).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.justifyContent(FlexAlign.SpaceBetween).padding(18).width('100%')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
              .onClick((): void => { this.themeChoiceOpen = true; })
            Row() {
              Image((this.dark ? $r('app.media.nav_folder_dark') : $r('app.media.nav_folder'))).width(20).height(20)
              Text('笔记布局').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text(this.listLayout ? '列表' : '宫格').fontSize(15).fontColor(this.dark ? '#A4AFA1' : '#677166')
              Text('›').fontSize(19).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.justifyContent(FlexAlign.SpaceBetween).padding(18).width('100%')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
              .onClick((): void => { this.layoutChoiceOpen = true; })
            Text('安全与隐私').fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            Row({ space: 12 }) {
              Image((this.dark ? $r('app.media.nav_inbox_dark') : $r('app.media.nav_inbox'))).width(20).height(20)
              Text('普通笔记').fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text('直接打开 · 本地加密').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.padding(18).width('100%').backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
            Row({ space: 12 }) {
              Image((this.dark ? $r('app.media.nav_lock_dark') : $r('app.media.nav_lock'))).width(20).height(20)
              Text('隐私笔记').fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text('支持系统验证').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.padding(18).width('100%').backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
              .onClick((): void => { this.settings = false; this.enterPrivate(); })
            Text('数据').fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            Row({ space: 12 }) {
              Image((this.dark ? $r('app.media.nav_archive_dark') : $r('app.media.nav_archive'))).width(20).height(20)
              Text('导入笔记').fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text('›').fontSize(19).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.padding(18).width('100%').backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
              .onClick((): void => { this.importTextFile(); })
            Text('其他').fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            Row({ space: 12 }) {
              Image((this.dark ? $r('app.media.nav_settings_dark') : $r('app.media.nav_settings'))).width(20).height(20)
              Text('关于拾页').fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
              Text('›').fontSize(19).fontColor(this.dark ? '#A4AFA1' : '#677166')
            }.padding(18).width('100%').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .borderRadius(18).onClick((): void => { this.settingsAbout = true; })
            Text('拾页 · Native\n给想法，留一页空白。\nOpenALC · MIT').fontSize(15).lineHeight(25)
              .fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%').padding(18)
          }
        }.width('100%').constraintSize({ minHeight: '100%' })
          .justifyContent(FlexAlign.Start).padding({ bottom: 24 })
      }.layoutWeight(1).width(this.tablet ? 700 : '100%').alignSelf(ItemAlign.Center)
    }.padding(24).layoutWeight(1).height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Row() {
        if (this.tablet) this.sidebar()
        if (!this.wideTablet && this.activeId !== '' && !this.settings) this.editorPane()
        else if (this.settings) this.settingsPane()
        else {
          this.listPane()
          if (this.wideTablet) {
            if (this.activeId !== '') this.editorPane()
            else Column({ space: 18 }) {
              Column() {
                Image((this.dark ? $r('app.media.nav_note_dark') : $r('app.media.nav_note'))).width(52).height(52)

              }.width(92).height(92).borderRadius(24)
                .justifyContent(FlexAlign.Center).alignItems(HorizontalAlign.Center)
                .backgroundColor(this.dark ? '#303830' : '#E8F6E1')
              Text('给想法，留一页空白。').fontSize(25).fontWeight(FontWeight.Bold)
                .fontColor(this.dark ? '#D8DED4' : '#141914')
              Text('选择一篇笔记，或写下此刻想到的事。').fontSize(15)
                .fontColor(this.dark ? '#A4AFA1' : '#677166')
              Button('＋  新建笔记').fontColor('#141914').backgroundColor('#54D81B')
                .borderRadius(14).margin({ top: 8 })
                .onClick((): void => this.createNote('richtext'))
            }.layoutWeight(1).height('100%').justifyContent(FlexAlign.Center)
              .alignItems(HorizontalAlign.Center)
              .backgroundColor(this.dark ? '#171B16' : '#FFFFFF')
          }
        }
      }.width('100%').height('100%')
        .onAreaChange((oldArea: Area, newArea: Area): void => {
          const width = parseFloat(String(newArea.width));
          const tablet = width >= 700;
          const wideTablet = width >= 1120;
          if (this.tablet !== tablet || this.wideTablet !== wideTablet) {
            this.tablet = tablet;
            this.wideTablet = wideTablet;
            this.sidebarWidth = tablet ? 224 : width * .56667;
          }
        })
      if (this.drawer && !this.tablet) {
        Row() {
          this.sidebar()
          Blank().onClick((): void => { this.drawer = false; })
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
      if (this.noteOptions && this.activeId !== '') {
        Column() {
          Blank().layoutWeight(1).onClick((): void => { this.noteOptions = false; this.movingGroup = false; })
          this.optionsSheet()
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
      if (this.homeOptions) {
        Column() {
          Blank().height(66).onClick((): void => { this.homeOptions = false; })
          Column({ space: 8 }) {
            Button() {
              Row({ space: 12 }) {
                Image((this.dark ? $r('app.media.nav_plus_dark') : $r('app.media.nav_plus'))).width(20).height(20)
                Text('新建分组').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914')
              }.width('100%')
            }.type(ButtonType.Normal).width('100%').backgroundColor(this.dark ? '#303830' : '#F4F6F2')
              .borderRadius(16).onClick((): void => { this.homeOptions = false; this.addingGroup = true; })
            Button() {
              Row({ space: 12 }) {
                Image((this.dark ? $r('app.media.nav_lock_dark') : $r('app.media.nav_lock'))).width(20).height(20)
                Text('隐私笔记').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914')
              }.width('100%')
            }.type(ButtonType.Normal).width('100%').backgroundColor(this.dark ? '#303830' : '#F4F6F2')
              .borderRadius(16).onClick((): void => { this.homeOptions = false; this.enterPrivate(); })
          }.width(this.tablet ? 240 : '60%').padding(10).borderRadius(22)
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .alignSelf(ItemAlign.End).margin({ right: 16 })
            .transition(TransitionEffect.OPACITY.animation({ duration: 180 }).combine(TransitionEffect.scale({ x: 0.82, y: 0.82 }).animation({ duration: 220 })))
          Blank().layoutWeight(1).onClick((): void => { this.homeOptions = false; })
        }.width('100%').height('100%').backgroundColor('#22000000')
      }
      if (this.addingGroup) {
        Column() {
          Column({ space: 18 }) {
            Text('新建分组').fontSize(22).fontWeight(FontWeight.Bold)
              .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%')
            TextInput({ placeholder: '分组名称', text: this.groupName })
              .fontColor(this.dark ? '#D8DED4' : '#141914')
              .placeholderColor(this.dark ? '#A4AFA1' : '#677166')
              .backgroundColor(this.dark ? '#303830' : '#F4F6F2')
              .onChange((value: string): void => { this.groupName = value; })
            Row({ space: 12 }) {
              Button('取消').layoutWeight(1).fontColor(this.dark ? '#D8DED4' : '#141914')
                .backgroundColor(this.dark ? '#303830' : '#F4F6F2')
                .onClick((): void => { this.addingGroup = false; this.groupName = ''; })
              Button('创建').layoutWeight(1).fontColor('#141914').backgroundColor('#54D81B')
                .onClick((): void => this.addGroup())
            }.width('100%')
          }.width(this.tablet ? 420 : '88%').padding(24).borderRadius(24)
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
        }.width('100%').height('100%').justifyContent(FlexAlign.Center)
          .alignItems(HorizontalAlign.Center).backgroundColor('#66000000')
      }
      if (this.layoutChoiceOpen) {
        Column() {
          Blank().layoutWeight(1).onClick((): void => { this.layoutChoiceOpen = false; })
          Column({ space: 8 }) {
            Text('笔记布局').fontSize(23).fontWeight(FontWeight.Bold)
              .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').padding({ bottom: 12 })
            this.layoutChoice('宫格', false)
            this.layoutChoice('列表', true)
            Button('取消').width('100%').fontColor(this.dark ? '#A4AFA1' : '#677166')
              .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
              .onClick((): void => { this.layoutChoiceOpen = false; })
          }.width(this.tablet ? 480 : '92%').padding(20).borderRadius(28)
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .alignSelf(ItemAlign.Center).margin({ bottom: 16 })
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
      if (this.themeChoiceOpen) {
        Column() {
          Blank().layoutWeight(1).onClick((): void => { this.themeChoiceOpen = false; })
          Column({ space: 8 }) {
            Text('外观').fontSize(23).fontWeight(FontWeight.Bold)
              .fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').padding({ bottom: 12 })
            this.themeChoice('浅色', false)
            this.themeChoice('深色', true)
            Button('取消').width('100%').fontColor(this.dark ? '#A4AFA1' : '#677166')
              .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
              .onClick((): void => { this.themeChoiceOpen = false; })
          }.width(this.tablet ? 480 : '92%').padding(20).borderRadius(28)
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .alignSelf(ItemAlign.Center).margin({ bottom: 16 })
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
      if (this.branchOptionsId !== '') {
        Column() {
          Blank().layoutWeight(1).onClick((): void => { this.branchOptionsId = ''; })
          this.branchSheet()
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
      if (this.batchMoveOpen || this.batchDeleteOpen) {
        Column() {
          Blank().layoutWeight(1).onClick((): void => { this.batchMoveOpen = false; this.batchDeleteOpen = false; })
          Column({ space: 8 }) {
            Text(this.batchDeleteOpen ? `删除 ${this.selectedIds.length} 篇笔记？` : '移动到')
              .fontSize(23).fontWeight(FontWeight.Bold).fontColor(this.dark ? '#D8DED4' : '#141914')
              .width('100%').padding({ bottom: 12 })
            if (this.batchMoveOpen) {
              Button('未分组').width('100%').fontColor(this.dark ? '#D8DED4' : '#141914')
                .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
                .onClick((): void => this.moveSelected(null))
              ForEach(this.groups, (group: NoteGroup) => {
                Button(group.name).width('100%').fontColor(this.dark ? '#D8DED4' : '#141914')
                  .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
                  .onClick((): void => this.moveSelected(group.id))
              }, (group: NoteGroup): string => group.id)
            } else {
              Button('删除笔记').width('100%').fontColor('#B84848')
                .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
                .onClick((): void => this.deleteSelected())
            }
            Button('取消').width('100%').fontColor(this.dark ? '#A4AFA1' : '#677166')
              .backgroundColor(this.dark ? '#303830' : '#F4F6F2').borderRadius(16)
              .onClick((): void => { this.batchMoveOpen = false; this.batchDeleteOpen = false; })
          }.width(this.tablet ? 480 : '92%').padding(20).borderRadius(28)
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .alignSelf(ItemAlign.Center).margin({ bottom: 16 })
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
    }.width('100%').height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }
}
