package wdl.model.draft3.elements

final case class OutputsSectionElement(outputs: Seq[OutputElement]) extends LanguageElement {
  override def children: Seq[LanguageElement] = outputs
}
