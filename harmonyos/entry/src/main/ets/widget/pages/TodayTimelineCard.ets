/** 今日时间轴卡片（4x2）：按节次纵向排列 */
let storage: LocalStorage = new LocalStorage();

@Entry(storage)
@Component
struct TodayTimelineCard {
  @LocalStorageProp('subtitle') subtitle: string = '';
  @LocalStorageProp('line1') line1: string = '';
  @LocalStorageProp('line2') line2: string = '';
  @LocalStorageProp('line3') line3: string = '';
  @LocalStorageProp('empty') empty: string = '';

  @Builder
  timelineRow(text: string) {
    Row() {
      Text('•').fontSize(12).margin({ right: 6 })
      Text(text).fontSize(11).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%')
    .margin({ top: 4 })
  }

  build() {
    Column() {
      Text('今日时间轴').fontSize(13).fontWeight(FontWeight.Bold)
      Text(this.subtitle).fontSize(10).opacity(0.7)
      if (this.empty.length > 0) {
        Text(this.empty).fontSize(12).margin({ top: 8 })
      } else {
        if (this.line1.length > 0) { this.timelineRow(this.line1) }
        if (this.line2.length > 0) { this.timelineRow(this.line2) }
        if (this.line3.length > 0) { this.timelineRow(this.line3) }
      }
    }
    .width('100%')
    .height('100%')
    .padding(12)
    .alignItems(HorizontalAlign.Start)
  }
}
