/** 本周课表微缩卡片（4x4）：列出今日课程缩略（点卡片进入 App 查看完整课表） */
let storage: LocalStorage = new LocalStorage();

@Entry(storage)
@Component
struct WeekGridCard {
  @LocalStorageProp('subtitle') subtitle: string = '';
  @LocalStorageProp('sectionCount') sectionCount: string = '12';
  @LocalStorageProp('line1') line1: string = '';
  @LocalStorageProp('line2') line2: string = '';
  @LocalStorageProp('line3') line3: string = '';
  @LocalStorageProp('line4') line4: string = '';
  @LocalStorageProp('empty') empty: string = '';

  build() {
    Column() {
      Text(this.subtitle).fontSize(11).fontWeight(FontWeight.Medium)
      if (this.empty.length > 0) {
        Text(this.empty).fontSize(12).margin({ top: 8 })
      } else {
        ForEach([this.line1, this.line2, this.line3, this.line4], (line: string) => {
          if (line.length > 0) {
            Text(line).fontSize(11).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 4 })
          }
        })
      }
    }
    .width('100%')
    .height('100%')
    .padding(12)
    .alignItems(HorizontalAlign.Start)
  }
}
