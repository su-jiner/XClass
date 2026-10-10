import { SectionTimeProfile, parseTimeTablesText, DEFAULT_PROFILE_ID } from '../core/Core.ets';
import { ProfileStore } from '../core/ProfileStore.ets';
import { currentPalette, ThemePalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';
import { router } from '@kit.ArkUI';

/** 节次时间表管理：多校区/楼栋各一套时间表，支持粘贴「XX校区上课时间」文本识别。 */
@Entry
@Component
struct TimeProfilesPage {
  @StorageProp('themeVersion') @Watch('reload') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State profiles: SectionTimeProfile[] = [];
  @State defaultId: string = DEFAULT_PROFILE_ID;
  @State input: string = '';
  @State toast: string = '';

  aboutToAppear(): void {
    this.reload();
  }

  reload(): void {
    this.profiles = ProfileStore.instance?.loadTimeProfiles() ?? [];
    this.defaultId = ProfileStore.instance?.loadDefaultProfileId() ?? DEFAULT_PROFILE_ID;
  }

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  parseFromText(): void {
    const parsed = parseTimeTablesText(this.input);
    if (parsed.length === 0) {
      this.toast = '未识别到时间表，请检查文本内容';
      return;
    }
    const store = ProfileStore.instance;
    const existing = (store?.loadTimeProfiles() ?? []).filter((p) => p.id !== DEFAULT_PROFILE_ID);
    parsed.forEach((profile) => {
      const index = existing.findIndex((item) => item.id === profile.id || item.name === profile.name);
      if (index >= 0) existing[index] = profile; else existing.push(profile);
    });
    store?.saveTimeProfiles(existing, store.loadCampusMap(), store.loadDefaultProfileId());
    this.toast = `已识别并保存 ${parsed.length} 套时间表`;
    this.input = '';
    this.reload();
  }

  setDefault(profile: SectionTimeProfile): void {
    const store = ProfileStore.instance;
    store?.saveTimeProfiles(
      (store.loadTimeProfiles()).filter((p) => p.id !== DEFAULT_PROFILE_ID),
      store.loadCampusMap(),
      profile.id
    );
    this.toast = `已把「${profile.name}」设为课表时间列显示的时间表`;
    this.reload();
  }

  removeProfile(profile: SectionTimeProfile): void {
    const store = ProfileStore.instance;
    const remaining = (store?.loadTimeProfiles() ?? []).filter((p) => p.id !== profile.id && p.id !== DEFAULT_PROFILE_ID);
    const campusMap = store?.loadCampusMap() ?? {};
    Object.keys(campusMap).forEach((key) => {
      if (campusMap[key] === profile.id) delete campusMap[key];
    });
    store?.saveTimeProfiles(remaining, campusMap, DEFAULT_PROFILE_ID);
    this.reload();
  }

  build() {
    Column() {
      Row() {
        Text('‹').fontSize(22).fontColor(this.palette().iconTint).onClick(() => router.back())
        Text('节次时间表').fontSize(20).fontWeight(FontWeight.Bold).fontColor(this.palette().text).margin({ left: 8 })
      }
      .width('100%')
      .padding(16)

      Text('多校区/楼栋可各配一套节次时间；今日页、提醒与课表时间列按课程所属校区取时间。')
        .fontSize(12).fontColor(this.palette().textSecondary)
        .width('100%').padding({ left: 16, right: 16, bottom: 8 })

      Scroll() {
        Column() {
          ForEach(this.profiles, (profile: SectionTimeProfile) => {
            Column() {
              Text(`${profile.name}${profile.id === this.defaultId ? '  ·  当前显示' : ''}`)
                .fontSize(15).fontWeight(FontWeight.Medium).fontColor(this.palette().text)
              Text(profile.blocks.map((b) => {
                const label = b.startSection === b.endSection ? `${b.startSection}节` : `${b.startSection}-${b.endSection}节`;
                return `${label} ${b.startTime}-${b.endTime}`;
              }).join('  '))
                .fontSize(12).fontColor(this.palette().textSecondary)
              Row() {
                Button('设为当前显示')
                  .fontSize(12).height(32)
                  .backgroundColor(this.palette().panelAlt).fontColor(this.palette().iconTint)
                  .onClick(() => this.setDefault(profile))
                if (profile.id !== DEFAULT_PROFILE_ID) {
                  Button('删除')
                    .fontSize(12).height(32).margin({ left: 8 })
                    .backgroundColor(this.palette().panelAlt).fontColor(this.palette().text)
                    .onClick(() => this.removeProfile(profile))
                }
              }
              .margin({ top: 6 })
            }
            .alignItems(HorizontalAlign.Start)
            .width('100%')
            .padding(12)
            .margin({ bottom: 8 })
            .backgroundColor(this.palette().panelAlt)
            .borderRadius(14)
          }, (profile: SectionTimeProfile) => profile.id)

          Text('从文本识别时间表').fontSize(14).fontColor(this.palette().text).margin({ top: 8 })
          TextArea({ text: this.input, placeholder: '粘贴如：真理道校区上课时间\n节次 | 教学楼、体育场 | 实训楼\n34节 | 10:25-12:00 | 10:10-11:30' })
            .height(110)
            .backgroundColor(this.palette().panelAlt)
            .fontColor(this.palette().text)
            .onChange((value: string) => { this.input = value; })
          Button('识别并保存')
            .width('100%').margin({ top: 8 })
            .backgroundColor(this.palette().buttonBackground)
            .fontColor(this.palette().buttonText)
            .onClick(() => this.parseFromText())
          if (this.toast.length > 0) {
            Text(this.toast).fontSize(12).fontColor(this.palette().iconTint).margin({ top: 8 })
          }
        }
        .width('100%')
        .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.palette().page)
  }
}
