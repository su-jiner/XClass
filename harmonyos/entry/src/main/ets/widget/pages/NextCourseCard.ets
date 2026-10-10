/** 下一节课卡片（2x1 / 1x1：课程名 + 时间 + 节次） */
let storage: LocalStorage = new LocalStorage();

@Entry(storage)
@Component
struct NextCourseCard {
  @LocalStorageProp('nextName') nextName: string = '今天没有课程安排';
  @LocalStorageProp('nextDetail') nextDetail: string = '';
  @LocalStorageProp('nextStart') nextStart: string = '--:--';

  build() {
    Column() {
      Text(this.nextName).fontSize(14).fontWeight(FontWeight.Bold).maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
      Text(this.nextDetail).fontSize(11).opacity(0.75).maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 2 })
      Text(`开始 ${this.nextStart}`).fontSize(11).margin({ top: 2 })
    }
    .width('100%')
    .height('100%')
    .padding(12)
    .alignItems(HorizontalAlign.Start)
  }
}
