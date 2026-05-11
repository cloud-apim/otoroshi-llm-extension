package com.cloud.apim.otoroshi.extensions.aigateway.assistant.docsearch

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.document.{Document => LuceneDocument, Field, StringField, TextField}
import org.apache.lucene.index.{DirectoryReader, IndexWriter, IndexWriterConfig}
import org.apache.lucene.queryparser.classic.{MultiFieldQueryParser, QueryParser, QueryParserBase}
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.similarities.BM25Similarity
import org.apache.lucene.store.ByteBuffersDirectory

import scala.collection.JavaConverters._

final class LexicalIndex private (
  directory: ByteBuffersDirectory,
  analyzer: Analyzer,
  reader: DirectoryReader
) {
  private val searcher = {
    val s = new IndexSearcher(reader)
    s.setSimilarity(new BM25Similarity())
    s
  }

  def search(rawQuery: String, topK: Int): Seq[(String, Float)] = {
    val q = rawQuery.trim
    if (q.isEmpty) return Seq.empty
    val parser = new MultiFieldQueryParser(LexicalIndex.searchFields, analyzer, LexicalIndex.fieldBoosts)
    parser.setDefaultOperator(QueryParser.Operator.OR)
    val parsed = parser.parse(QueryParserBase.escape(q))
    val hits = searcher.search(parsed, topK).scoreDocs
    val storedFields = searcher.storedFields()
    hits.iterator.map { sd =>
      val doc = storedFields.document(sd.doc)
      (doc.get("docId"), sd.score)
    }.toSeq
  }

  def close(): Unit = {
    reader.close()
    directory.close()
  }
}

object LexicalIndex {
  private val searchFields: Array[String] = Array("title", "breadcrumb", "text")

  private val fieldBoosts: java.util.Map[String, java.lang.Float] = Map(
    "title" -> java.lang.Float.valueOf(2.0f),
    "breadcrumb" -> java.lang.Float.valueOf(1.2f),
    "text" -> java.lang.Float.valueOf(1.0f)
  ).asJava

  def build(chunks: Seq[DocChunk]): LexicalIndex = {
    val directory = new ByteBuffersDirectory()
    val analyzer = new StandardAnalyzer()
    val cfg = new IndexWriterConfig(analyzer)
    cfg.setSimilarity(new BM25Similarity())
    val writer = new IndexWriter(directory, cfg)
    try {
      chunks.foreach { c =>
        val doc = new LuceneDocument()
        doc.add(new StringField("docId", c.docId, Field.Store.YES))
        doc.add(new TextField("title", c.title, Field.Store.NO))
        if (c.breadcrumb.nonEmpty) {
          doc.add(new TextField("breadcrumb", c.breadcrumb.mkString(" "), Field.Store.NO))
        }
        if (c.text.nonEmpty) {
          doc.add(new TextField("text", c.text, Field.Store.NO))
        }
        writer.addDocument(doc)
      }
      writer.commit()
    } finally {
      writer.close()
    }
    val reader = DirectoryReader.open(directory)
    new LexicalIndex(directory, analyzer, reader)
  }
}
