import { TimetablePage } from './TimetablePage.ets';
import { TodayPage } from './TodayPage.ets';
import { ToolsPage } from './ToolsPage.ets';
import { SettingsPage } from './SettingsPage.ets';
import { currentPalette } from '../model/Theme.ets';
import { Prefs } from '../model/Prefs.ets';

@Entry
@Component
struct Index {
  @StorageProp('themeVersion') themeVersion: number = 0;
  @StorageProp('systemDark') systemDark: boolean = false;
  @State selected: number = Prefs.defaultPage() === 'today' ? 1 : 0;

  palette(): ThemePalette {
    return currentPalette(Prefs.theme(), Prefs.darkMode(), this.systemDark);
  }

  build() {
    Tabs({ barPosition: BarPosition.End, index: this.selected }) {
      TabContent() {
        TimetablePage()
      }
      .tabBar(this.tabLabel('课表'))

      TabContent() {
        TodayPage()
      }
      .tabBar(this.tabLabel('日程'))

      TabContent() {
        ToolsPage()
      }
      .tabBar(this.tabLabel('工具'))

      TabContent() {
        SettingsPage()
      }
      .tabBar(this.tabLabel('设置'))
    }
    .onChange((index: number) => {
      this.selected = index;
    })
    .barBackgroundColor(this.palette().nav)
    .backgroundColor(this.palette().page)
  }

  @Builder
  tabLabel(text: string) {
    Text(text)
      .fontSize(13)
      .fontColor(this.palette().textSecondary)
  }
}
