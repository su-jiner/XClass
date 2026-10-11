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
    Column() {
      Tabs({ barPosition: BarPosition.End, index: this.selected }) {
        TabContent() {
          TimetablePage()
        }
        .tabBar(this.tabLabel('课表', 0))

        TabContent() {
          TodayPage()
        }
        .tabBar(this.tabLabel('日程', 1))

        TabContent() {
          ToolsPage()
        }
        .tabBar(this.tabLabel('工具', 2))

        TabContent() {
          SettingsPage()
        }
        .tabBar(this.tabLabel('设置', 3))
      }
      .onChange((index: number) => {
        this.selected = index;
      })
      .barBackgroundColor(Color.Transparent)
      .barHeight(64)
      .divider(null)
      .backgroundColor(this.palette().page)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  tabLabel(text: string, index: number) {
    Column() {
      Text(text)
        .fontSize(13)
        .fontWeight(index === this.selected ? FontWeight.Bold : FontWeight.Regular)
        .fontColor(index === this.selected ? this.palette().buttonText : this.palette().textSecondary)
        .padding({ left: 20, right: 20, top: 8, bottom: 8 })
        .borderRadius(20)
        .backgroundColor(index === this.selected ? this.palette().buttonBackground : Color.Transparent)
    }
    .justifyContent(FlexAlign.Center)
    .width('100%')
    .height('100%')
  }
}
