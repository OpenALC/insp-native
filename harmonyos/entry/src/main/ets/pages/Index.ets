import { common } from '@kit.AbilityKit';
import { display } from '@kit.ArkUI';
import { NativeStore, NativeNote, NoteGroup, MindBranch, newId, newNote } from '../data/NativeStore';

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

  aboutToAppear(): void {
    const screen = display.getDefaultDisplaySync();
    this.tablet = screen.width / screen.densityPixels >= 600;
    NativeStore.open(getContext(this) as common.UIAbilityContext).then((store: NativeStore): void => {
      this.store = store;
      this.refresh();
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
      const wordsMatch = this.search === '' || (note.title + ' ' + note.body).toLowerCase().includes(this.search.toLowerCase());
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
    note.title = this.title.trim() || (note.kind === 'mindmap' ? '未命名的灵感笔记' : '未命名笔记');
    note.body = this.body;
    note.updatedAt = Date.now();
    this.storeSave();
  }

  private storeSave(): void {
    if (this.privateOpen) this.store?.savePrivate();
    else this.store?.saveNormal();
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
    if (note.kind !== 'mindmap') return note.body.replace(/\s+/g, ' ').slice(0, 180);
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
        TextArea({ text: this.body, placeholder: '开始记录…' })
          .fontSize(18).fontColor(this.dark ? '#D8DED4' : '#141914').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
          .padding(22).layoutWeight(1).width('100%')
          .onChange((value: string): void => { this.body = value; this.scheduleSave(); })
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
        Text('暗色模式').fontSize(17).fontColor(this.dark ? '#D8DED4' : '#141914').layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.dark }).onChange((value: boolean): void => { this.dark = value; })
      }.padding(18).backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18).width('100%')
      Text('关于拾页 Native\n原生单机版 · MIT\nOpenALC').fontSize(15).lineHeight(26)
        .fontColor(this.dark ? '#A4AFA1' : '#677166').width('100%').padding(18).backgroundColor(this.dark ? '#252B24' : '#FFFFFF').borderRadius(18)
      Blank()
    }.padding(24).width('100%').height('100%').backgroundColor(this.dark ? '#171B16' : '#F5F7F2')
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

