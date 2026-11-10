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
  @State private treeMode: boolean = false;
  @State private rows: BranchRow[] = [];
  @State private dark: boolean = false;
  @State private tablet: boolean = false;
  @State private drawer: boolean = false;
  @State private settings: boolean = false;
  @State private privateOpen: boolean = false;
  @State private addingGroup: boolean = false;
  @State private groupName: string = '';
  @State private search: string = '';
  @State private noteOptions: boolean = false;
  @State private movingGroup: boolean = false;
  private saveTimer: number = -1;
  private richController: RichEditorController = new RichEditorController();
  private richOptions: RichEditorOptions = { controller: this.richController };
  private richReady: boolean = false;
  private richLoading: boolean = false;

  aboutToAppear(): void {
    const screen = display.getDefaultDisplaySync();
    this.tablet = screen.width / screen.densityPixels >= 600;
    NativeStore.open(getContext(this) as common.UIAbilityContext).then((store: NativeStore): void => {
      this.store = store;
      this.dark = store.dark;
      this.syncSystemBars();
      this.refresh();
    });
  }

  private async syncSystemBars(): Promise<void> {
    const context = getContext(this) as common.UIAbilityContext;
    const current = await window.getLastWindow(context);
    await current.setWindowSystemBarProperties({
      statusBarColor: this.dark ? '#171B16' : '#F5F7F2',
      navigationBarColor: this.dark ? '#171B16' : '#F5F7F2',
      statusBarContentColor: this.dark ? '#D8DED4' : '#141914',
      navigationBarContentColor: this.dark ? '#D8DED4' : '#141914',
      enableStatusBarAnimation: true,
      enableNavigationBarAnimation: true
    });
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
    if (this.settings) {
      this.settings = false;
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
      const groupMatches = this.groupId === 'all' || note.groupId === this.groupId;
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
    const note = newNote(kind, this.groupId === 'all' ? null : this.groupId, this.privateOpen);
    if (this.privateOpen) this.store.privateNotes.unshift(note);
    else this.store.notes.unshift(note);
    this.storeSave();
    this.refresh();
    this.open(note);
  }

  private open(note: NativeNote): void {
    this.saveNow();
    this.activeId = note.id;
    this.title = note.title;
    this.body = note.body;
    this.treeMode = false;
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
    this.rows = [];
    this.noteOptions = false;
    this.movingGroup = false;
    this.refresh();
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
      this.richController.addTextSpan(document.text.slice(start, end), {
        style: {
          fontColor: this.dark ? '#D8DED4' : '#141914',
          fontSize: heading ? 24 : 18,
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
  }

  private formatRich(kind: string): void {
    const selection = this.richController.getSelection().selection;
    if (selection[0] === selection[1]) return;
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
      return;
    }
    const rows: BranchRow[] = [];
    const visit = (node: MindBranch, depth: number): void => {
      if (depth > 0) rows.push({ id: node.id, text: node.text, depth: depth });
      node.children.forEach((child: MindBranch): void => visit(child, depth + 1));
    };
    visit(this.mindRoot(), 0);
    this.rows = rows;
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
    parent.children.push({ id: newId(), text: '新分支', children: [] });
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
    this.updateMind(root);
  }

  @Builder private sidebar(): void {
    Column({ space: 12 }) {
      Text('拾页 · Inspiration').fontSize(18).fontWeight(FontWeight.Bold).fontColor(this.dark ? '#D8DED4' : '#141914')
      Text('by OpenALC').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166').alignSelf(ItemAlign.End)
      Blank().height(14)
      this.sidebarItem('全部笔记', 'all')
      ForEach(this.groups, (group: NoteGroup) => {
        this.sidebarItem(group.name, group.id)
      }, (group: NoteGroup): string => group.id)
      if (this.addingGroup) {
        Row() {
          TextInput({ placeholder: '分组名称', text: this.groupName })
            .onChange((value: string): void => { this.groupName = value; })
            .layoutWeight(1)
          Button('确定').fontColor('#141914').backgroundColor('#54D81B').borderRadius(14)
            .onClick((): void => this.addGroup())
        }
      } else {
        Button('＋ 新建分组').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(14)
          .onClick((): void => { this.addingGroup = true; })
      }
      Blank()
      if (this.privateOpen) {
        Button('退出隐私笔记').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(14).onClick((): void => this.leavePrivate())
      } else {
        Button('隐私笔记').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(14).onClick((): void => { this.enterPrivate(); })
      }
      Button('设置').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
        .borderRadius(14).onClick((): void => { this.settings = true; this.drawer = false; })
    }
    .padding(20).width(this.tablet ? 210 : 260).height('100%')
    .backgroundColor(this.dark ? '#20271E' : '#EBF1E8')
  }

  @Builder private sidebarItem(label: string, id: string): void {
    Text(label).fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').width('100%').padding(12)
      .backgroundColor(this.groupId === id ? '#4454D81B' : (this.dark ? '#20271E' : '#EBF1E8'))
      .borderRadius(14)
      .onClick((): void => { this.groupId = id; this.drawer = false; this.activeId = ''; })
  }

  @Builder private listPane(): void {
    Column() {
      Row() {
        if (!this.tablet) {
          Button('☰').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => { this.drawer = true; })
        }
        Text(this.privateOpen ? '隐私笔记' : '笔记').fontSize(28).fontWeight(FontWeight.Bold)
          .fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
        Text(this.visibleNotes().length.toString()).fontSize(15).fontColor(this.dark ? '#A4AFA1' : '#677166')
      }.width('100%').padding({ left: 18, right: 18, top: 18, bottom: 12 })
      TextInput({ placeholder: '搜索笔记', text: this.search })
        .onChange((value: string): void => { this.search = value; })
        .margin({ left: 16, right: 16, bottom: 12 })
      List({ space: 10 }) {
        ForEach(this.visibleNotes(), (note: NativeNote) => {
          ListItem() {
            Column({ space: 9 }) {
              Text(note.title).fontSize(18).fontWeight(FontWeight.Medium).fontColor(this.dark ? '#D8DED4' : '#141914')
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
              Text(this.preview(note)).fontSize(14).fontColor(this.dark ? '#A4AFA1' : '#677166').maxLines(4)
                .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
              Text(note.kind === 'mindmap' ? '灵感笔记' : '笔记').fontSize(12).fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%')
            }
            .padding(18).width('100%').backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(20)
            .onClick((): void => this.open(note))
          }
        }, (note: NativeNote): string => note.id)
      }.layoutWeight(1).padding({ left: 14, right: 14 })
      Row({ space: 10 }) {
        Button('＋ 笔记').layoutWeight(1).fontColor('#141914').backgroundColor('#54D81B')
          .onClick((): void => this.createNote('richtext'))
        Button('◇ 灵感笔记').layoutWeight(1).fontColor('#141914').backgroundColor('#54D81B')
          .onClick((): void => this.createNote('mindmap'))
      }.width('100%').padding(16)
    }.width(this.tablet ? 310 : '100%').height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }

  private preview(note: NativeNote): string {
    if (note.kind !== 'mindmap') return readRich(note.body).text.replace(/\s+/g, ' ').slice(0, 180);
    const root = (JSON.parse(note.body) as MindDocument).root;
    return root.children.map((node: MindBranch): string => node.text).join(' · ');
  }

  @Builder private editorPane(): void {
    Column() {
      Row({ space: 8 }) {
        if (!this.tablet) {
          Button('‹').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => this.leaveEditor())
        }
        TextInput({ text: this.title, placeholder: '笔记名称' })
          .fontSize(22).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
          .backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
          .onChange((value: string): void => {
            this.title = value;
            if (this.activeNote()?.kind === 'mindmap') this.updateMind(this.mindRoot(), false);
            else this.scheduleSave();
          })
        if (this.activeNote()?.kind === 'mindmap') {
          Button(this.treeMode ? '☷' : '◇').type(ButtonType.Normal)
            .fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => { this.treeMode = !this.treeMode; })
        }
        Button('完成').type(ButtonType.Normal).fontColor('#141914').backgroundColor('#54D81B')
          .onClick((): void => this.leaveEditor())
        Button('⋯').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
          .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
          .onClick((): void => { this.noteOptions = !this.noteOptions; })
      }.width('100%').padding(12)
      if (this.noteOptions) {
        Row({ space: 8 }) {
          Button('移动分组').fontColor(this.dark ? '#D8DED4' : '#141914')
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => { this.movingGroup = !this.movingGroup; })
          Button('删除笔记').fontColor(this.dark ? '#D8DED4' : '#141914')
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => this.deleteCurrent())
          Button('导出纯文本').fontColor(this.dark ? '#D8DED4' : '#141914')
            .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
            .onClick((): void => { this.exportTextFile(); })
        }.width('100%').padding({ left: 18, right: 18, bottom: 10 })
        if (this.movingGroup) {
          Scroll() {
            Row({ space: 8 }) {
              Button('未分组').onClick((): void => this.moveCurrent(null))
              ForEach(this.groups, (group: NoteGroup) => {
                Button(group.name).onClick((): void => this.moveCurrent(group.id))
              }, (group: NoteGroup): string => group.id)
            }
          }.scrollable(ScrollDirection.Horizontal).padding({ left: 18, right: 18, bottom: 10 })
        }
      }
      if (this.activeNote()?.kind === 'mindmap') {
        Scroll() {
          Column({ space: 8 }) {
            if (this.treeMode) {
              Text(this.title).fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.dark ? '#D8DED4' : '#141914')
                .padding(16).backgroundColor('#4454D81B').borderRadius(16)
            }
            ForEach(this.rows, (row: BranchRow) => {
              Row({ space: 4 }) {
                Text(this.treeMode ? '├' : '•').fontColor('#54D81B')
                TextInput({ text: row.text }).fontSize(16).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
                  .backgroundColor(this.treeMode ? (this.dark ? '#252B24' : '#FFFFFF') : (this.dark ? '#171B16' : '#F5F7F2'))
                  .onChange((value: string): void => this.editBranch(row.id, value))
                Button('＋').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
                  .onClick((): void => this.addBranch(row.id))
                Button('→').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
                  .onClick((): void => this.moveBranch(row.id, true))
                Button('←').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
                  .onClick((): void => this.moveBranch(row.id, false))
                Button('×').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914')
                  .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
                  .onClick((): void => this.removeBranch(row.id))
              }.width('100%').padding({ left: row.depth * 18, right: 8 })
            }, (row: BranchRow): string => row.id)
            Button('＋ 添加分支').fontColor('#141914').backgroundColor('#54D81B')
              .onClick((): void => this.addBranch())
          }.width('100%').padding(18)
        }.layoutWeight(1)
      } else {
        RichEditor(this.richOptions)
          .placeholder('开始记录…')
          .caretColor('#54D81B')
          .backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
          .padding(22).layoutWeight(1).width('100%')
          .onReady((): void => { this.richReady = true; this.loadRich(); })
          .onDidChange((): void => this.changedRich())
        Scroll() {
          Row({ space: 8 }) {
            Button('H').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('heading'))
            Button('B').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('bold'))
            Button('I').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('italic'))
            Button('U').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('underline'))
            Button('S').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('strike'))
            Button('M').fontColor(this.dark ? '#D8DED4' : '#141914')
              .backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
              .onClick((): void => this.formatRich('highlight'))
          }
        }.scrollable(ScrollDirection.Horizontal).width('100%').padding(12)
      }
    }.layoutWeight(1).height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }

  @Builder private settingsPane(): void {
    Column({ space: 18 }) {
      Row() {
        Button('‹').type(ButtonType.Normal).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#252B24' : '#FFFFFF')
          .onClick((): void => { this.settings = false; })
        Text('设置').fontSize(25).fontWeight(FontWeight.Bold).fontColor(this.dark ? '#D8DED4' : '#141914')
      }.width('100%')
      Row() {
        Text('暗色模式').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914')
        Toggle({ type: ToggleType.Switch, isOn: this.dark }).selectedColor('#54D81B')
          .width(54)
          .onChange((value: boolean): void => {
            this.dark = value;
            this.syncSystemBars();
            if (this.store !== null) { this.store.dark = value; this.store.saveNormal(); }
          })
      }.justifyContent(FlexAlign.SpaceBetween).padding(18)
        .backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18).width('100%')
      Button('从纯文本 / Markdown 导入').fontColor('#141914').backgroundColor('#54D81B')
        .onClick((): void => { this.importTextFile(); })
      Text('关于拾页 Native\n原生单机版 · MIT\nOpenALC').fontSize(15).lineHeight(26)
        .fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%').padding(18).backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
      Blank()
    }.padding(24).layoutWeight(1).height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }

  build() {
    Stack({ alignContent: Alignment.TopStart }) {
      Row() {
        if (this.tablet) this.sidebar()
        if (!this.tablet && this.activeId !== '' && !this.settings) this.editorPane()
        else if (this.settings) this.settingsPane()
        else {
          this.listPane()
          if (this.tablet) {
            if (this.activeId !== '') this.editorPane()
            else Column() { Text('选择笔记，开始记录灵感').fontSize(18).fontColor(this.dark ? '#A4AFA1' : '#677166') }
              .layoutWeight(1).height('100%').justifyContent(FlexAlign.Center).backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
          }
        }
      }.width('100%').height('100%')
      if (this.drawer && !this.tablet) {
        Row() {
          this.sidebar()
          Blank().onClick((): void => { this.drawer = false; })
        }.width('100%').height('100%').backgroundColor('#66000000')
      }
    }.width('100%').height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
  }
}
