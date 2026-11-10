import { common } from '@kit.AbilityKit';
import { DeviceSecurity } from './DeviceSecurity';

export interface NoteGroup {
  id: string;
  name: string;
}

export interface MindBranch {
  id: string;
  text: string;
  children: MindBranch[];
}

export interface NativeNote {
  id: string;
  title: string;
  body: string;
  kind: string;
  groupId: string | null;
  private: boolean;
  updatedAt: number;
}

export class NativeStore {
  private readonly security: DeviceSecurity;
  groups: NoteGroup[] = [];
  notes: NativeNote[] = [];
  privateNotes: NativeNote[] = [];
  dark: boolean = false;
  listLayout: boolean = false;
  private pending: Promise<void> = Promise.resolve();

  private constructor(security: DeviceSecurity) {
    this.security = security;
  }

  static async open(context: common.UIAbilityContext): Promise<NativeStore> {
    const store = new NativeStore(await DeviceSecurity.open(context));
    const normal = await store.security.getSecret('native-workspace-v1');
    if (normal !== null) {
      const data = JSON.parse(normal) as WorkspaceData;
      store.groups = data.groups;
      store.notes = data.notes;
      store.dark = data.dark ?? false;
      store.listLayout = data.listLayout ?? false;
    }
    return store;
  }

  async unlockPrivate(): Promise<boolean> {
    if (!await this.security.authenticate()) {
      return false;
    }
    const value = await this.security.getSecret('native-private-v1');
    this.privateNotes = value === null ? [] : JSON.parse(value) as NativeNote[];
    return true;
  }

  lockPrivate(): void {
    this.privateNotes = [];
  }

  saveNormal(): Promise<void> {
    const value = JSON.stringify({ groups: this.groups, notes: this.notes, dark: this.dark, listLayout: this.listLayout });
    this.pending = this.pending.then((): Promise<void> => this.security.setSecret('native-workspace-v1', value));
    return this.pending;
  }

  savePrivate(): Promise<void> {
    const value = JSON.stringify(this.privateNotes);
    this.pending = this.pending.then((): Promise<void> => this.security.setSecret('native-private-v1', value));
    return this.pending;
  }

  flush(): Promise<void> {
    return this.pending;
  }

  close(): void {
    this.lockPrivate();
    this.security.close();
  }
}

interface WorkspaceData {
  groups: NoteGroup[];
  notes: NativeNote[];
  dark?: boolean;
  listLayout?: boolean;
}

export function newId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2);
}

export function newNote(kind: string, groupId: string | null, privacy: boolean): NativeNote {
  const title = kind === 'mindmap' ? '未命名的灵感笔记' : '未命名笔记';
  const root: MindBranch = { id: newId(), text: title, children: [] };
  return {
    id: newId(), title: title,
    body: kind === 'mindmap' ? JSON.stringify({ type: 'mindmap', root: root })
      : JSON.stringify({ version: 1, text: '', spans: [] }),
    kind: kind, groupId: groupId, private: privacy, updatedAt: Date.now()
  };
}
