/**
 * 工具页文本录入子页：接收标题与格式提示，输入后写回 AppStorage，
 * ToolsPage.onPageShow 读取并应用。
 */
import {
  currentPalette, ThemePalette,
} from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { router } from '@kit.ArkUI';

@Entry
@Component
struct ToolsInputPage {
  @StorageProp('themeVersion') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State title: string = '输入';
  @State placeholder: string = '';
  @State text: string = '';
  @State op: string = '';
  private inputController: TextInputController = new TextInputController();

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  onPageShow(): void {
    const params = router.getParams() as Record<string, string>;
    if (params && params['title']) this.title = decodeURIComponent(params['title']);
    if (params && params['placeholder']) this.placeholder = decodeURIComponent(params['placeholder']);
    if (params && params['op']) this.op = decodeURIComponent(params['op']);
    this.text = '';
  }

  confirm(): void {
    AppStorage.setOrCreate('pendingToolsInput', this.text.trim());
    AppStorage.setOrCreate('pendingToolsOp', this.op);
    router.back();
  }

  build() {
    Column() {
      Row() {
        Text('取消').fontSize(16).fontColor(this.palette().iconTint).onClick(() => router.back())
        Blank()
        Text('确定').fontSize(16).fontWeight(FontWeight.Bold).fontColor(this.palette().iconTint).onClick(() => this.confirm())
      }
      .width('100%')
      .padding({ top: 10, bottom: 10 })

      Text(this.title)
        .fontSize(20).fontWeight(FontWeight.Bold)
        .fontColor(this.palette().textPrimary)
        .width('100%')
        .margin({ top: 8 })

      Text('支持输入：' + this.placeholder)
        .fontSize(12)
        .fontColor(this.palette().textSecondary)
        .width('100%')
        .margin({ top: 6 })

      TextInput({ placeholder: this.placeholder, text: this.text, controller: this.inputController })
        .width('100%')
        .height(48)
        .fontSize(15)
        .fontColor(this.palette().textPrimary)
        .backgroundColor(this.palette().panelAlt)
        .margin({ top: 14 })
        .onChange((value: string) => {
          this.text = value;
        })

      Text('多项用 / 分隔，如：高等数学/4/89/2026-2027-1')
        .fontSize(11)
        .fontColor(this.palette().textSecondary)
        .width('100%')
        .margin({ top: 8 })
    }
    .padding(16)
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }
}
