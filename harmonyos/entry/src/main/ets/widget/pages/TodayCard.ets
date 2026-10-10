/** 今日课程卡片（2x2 / 4x2 / 4x4 自适应内容行数） */
let storage: LocalStorage = new LocalStorage();

@Entry(storage)
@Component
struct TodayCard {
  @LocalStorageProp('title') title: string = 'XClass';
  @LocalStorageProp('subtitle') subtitle: string = '';
  @LocalStorageProp('line1') line1: string = '';
  @LocalStorageProp('line2') line2: string = '';
  @LocalStorageProp('line3') line3: string = '';
  @LocalStorageProp('line4') line4: string = '';
  @LocalStorageProp('empty') empty: string = '';

  build() {
    Column() {
      Text(this.title).fontSize(14).fontWeight(FontWeight.Bold)
      Text(this.subtitle).fontSize(10).opacity(0.7)
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
