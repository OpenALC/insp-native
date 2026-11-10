import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { huks } from '@kit.UniversalKeystoreKit';
import { userAuth } from '@kit.UserAuthenticationKit';

const KEY_ALIAS: string = 'insp.device-secrets.aes-gcm.v1';
const AUTH_LEVEL: userAuth.AuthTrustLevel = userAuth.AuthTrustLevel.ATL2;

export class DeviceSecurity {
  private readonly store: preferences.Preferences;
  private readonly base64: util.Base64Helper = new util.Base64Helper();
  private secretQueue: Promise<void> = Promise.resolve();
  private auth: userAuth.UserAuthInstance | null = null;
  private authCallback: userAuth.IAuthCallback | null = null;
  private authResolve: ((success: boolean) => void) | null = null;
  private closed: boolean = false;

  private constructor(store: preferences.Preferences) {
    this.store = store;
  }

  static async open(context: common.UIAbilityContext): Promise<DeviceSecurity> {
    return new DeviceSecurity(await preferences.getPreferences(context, { name: 'device-secrets' }));
  }

  private enqueueSecret<T>(action: () => Promise<T>): Promise<T> {
    const operation = this.secretQueue.then(() => action());
    this.secretQueue = operation.then((): void => {}, (_error: Error): void => {});
    return operation;
  }

  getSecret(id: string): Promise<string | null> {
    return this.enqueueSecret(async (): Promise<string | null> => {
      const stored = await this.store.get(id, '') as string;
      if (stored === '') {
        return null;
      }
      if (!stored.startsWith('v1:')) {
        throw new Error('设备密文版本不受支持。');
      }
      const bytes = this.base64.decodeSync(stored.slice(3));
      if (bytes.length < 28) {
        throw new Error('设备密文已损坏。');
      }
      const properties = this.cipherProperties(id, bytes.slice(0, 12), huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT);
      properties.push({ tag: huks.HuksTag.HUKS_TAG_AE_TAG, value: bytes.slice(bytes.length - 16) });
      // HUKS takes the GCM authentication tag separately from the ciphertext.
      const plaintext = await this.crypt(properties, bytes.slice(12, bytes.length - 16));
      try {
        return JSON.parse(util.TextDecoder.create('utf-8').decodeToString(plaintext)) as string;
      } finally {
        plaintext.fill(0);
      }
    });
  }

  setSecret(id: string, value: string): Promise<void> {
    return this.enqueueSecret(async (): Promise<void> => {
      let keyExists: boolean;
      try {
        keyExists = await huks.isKeyItemExist(KEY_ALIAS, { properties: [] });
      } catch (error) {
        if ((error as BusinessError).code !== huks.HuksExceptionErrCode.HUKS_ERR_CODE_ITEM_NOT_EXIST) {
          throw error as Error;
        }
        keyExists = false;
      }
      if (!keyExists) {
        // Generation by alias persists the non-exported AES key inside HUKS.
        await huks.generateKeyItem(KEY_ALIAS, {
          properties: [
            { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
            { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
            {
              tag: huks.HuksTag.HUKS_TAG_PURPOSE,
              value: huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT | huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_DECRYPT
            },
            { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
            { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM }
          ]
        });
      }
      const nonce = cryptoFramework.createRandom().generateRandomSync(12).data;
      const plaintext = new util.TextEncoder().encodeInto(JSON.stringify(value));
      let ciphertext: Uint8Array;
      try {
        ciphertext = await this.crypt(this.cipherProperties(id, nonce, huks.HuksKeyPurpose.HUKS_KEY_PURPOSE_ENCRYPT), plaintext);
      } finally {
        plaintext.fill(0);
      }
      const envelope = new Uint8Array(nonce.length + ciphertext.length);
      envelope.set(nonce);
      envelope.set(ciphertext, nonce.length);
      // Preferences receives only nonce + authenticated ciphertext, never the secret.
      await this.store.put(id, 'v1:' + this.base64.encodeToStringSync(envelope));
      await this.store.flush();
    });
  }

  private cipherProperties(id: string, nonce: Uint8Array, purpose: huks.HuksKeyPurpose): huks.HuksParam[] {
    return [
      { tag: huks.HuksTag.HUKS_TAG_ALGORITHM, value: huks.HuksKeyAlg.HUKS_ALG_AES },
      { tag: huks.HuksTag.HUKS_TAG_KEY_SIZE, value: huks.HuksKeySize.HUKS_AES_KEY_SIZE_256 },
      { tag: huks.HuksTag.HUKS_TAG_PURPOSE, value: purpose },
      { tag: huks.HuksTag.HUKS_TAG_PADDING, value: huks.HuksKeyPadding.HUKS_PADDING_NONE },
      { tag: huks.HuksTag.HUKS_TAG_BLOCK_MODE, value: huks.HuksCipherMode.HUKS_MODE_GCM },
      { tag: huks.HuksTag.HUKS_TAG_NONCE, value: nonce },
      { tag: huks.HuksTag.HUKS_TAG_ASSOCIATED_DATA, value: new util.TextEncoder().encodeInto(KEY_ALIAS + ':' + id) }
    ];
  }

  private async crypt(properties: huks.HuksParam[], input: Uint8Array): Promise<Uint8Array> {
    const session = await huks.initSession(KEY_ALIAS, { properties });
    const result = await huks.finishSession(session.handle, { properties, inData: input });
    return result.outData as Uint8Array;
  }

  private supports(type: userAuth.UserAuthType): boolean {
    try {
      userAuth.getAvailableStatus(type, AUTH_LEVEL);
      return true;
    } catch (error) {
      const code = (error as BusinessError).code;
      if (code === userAuth.UserAuthResultCode.TYPE_NOT_SUPPORT ||
        code === userAuth.UserAuthResultCode.TRUST_LEVEL_NOT_SUPPORT ||
        code === userAuth.UserAuthResultCode.NOT_ENROLLED ||
        code === userAuth.UserAuthResultCode.PIN_EXPIRED) {
        return false;
      }
      throw new Error('系统认证状态查询失败（' + code + '）：' + (error as BusinessError).message);
    }
  }

  authStatus(): boolean {
    return this.supports(userAuth.UserAuthType.PIN);
  }

  authenticate(): Promise<boolean> {
    if (this.closed || this.auth !== null || !this.authStatus()) {
      return Promise.resolve(false);
    }
    const types: userAuth.UserAuthType[] = [userAuth.UserAuthType.PIN];
    for (const type of [userAuth.UserAuthType.FINGERPRINT, userAuth.UserAuthType.FACE]) {
      if (this.supports(type)) {
        types.push(type);
      }
    }
    return new Promise<boolean>((resolve): void => {
      this.authResolve = resolve;
      try {
        this.auth = userAuth.getUserAuthInstance({
          challenge: cryptoFramework.createRandom().generateRandomSync(32).data,
          authType: types,
          authTrustLevel: AUTH_LEVEL
          // No reuseUnlockResult: every request requires fresh system authentication.
        }, { title: '验证身份以打开隐私笔记' });
        const callback: userAuth.IAuthCallback = {
          onResult: (result: userAuth.UserAuthResult): void => {
            this.finishAuthentication(result.result === userAuth.UserAuthResultCode.SUCCESS);
          }
        };
        this.auth.on('result', callback);
        this.authCallback = callback;
        this.auth.start();
      } catch (_error) {
        this.finishAuthentication(false);
      }
    });
  }

  private finishAuthentication(success: boolean): void {
    const auth = this.auth;
    const callback = this.authCallback;
    const resolve = this.authResolve;
    this.auth = null;
    this.authCallback = null;
    this.authResolve = null;
    try {
      if (auth !== null && callback !== null) {
        auth.off('result', callback);
      }
    } finally {
      if (resolve !== null) {
        resolve(success && !this.closed);
      }
    }
  }

  close(): void {
    this.closed = true;
    try {
      if (this.auth !== null) {
        this.auth.cancel();
      }
    } finally {
      this.finishAuthentication(false);
    }
  }
}
