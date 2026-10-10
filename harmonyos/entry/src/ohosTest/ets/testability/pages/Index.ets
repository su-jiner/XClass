@Entry
@Component
struct TestIndex {
  build() {
    Column() {
      Text('XClass Unit Tests')
        .fontSize(16)
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
  }
}
