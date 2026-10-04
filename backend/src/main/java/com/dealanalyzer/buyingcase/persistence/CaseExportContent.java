package com.dealanalyzer.buyingcase.persistence;

import java.util.List;

/** Retained case content another module adds to the case export archive, such as relay messages. */
public interface CaseExportContent {
  List<File> files(String caseId);

  record File(String path, byte[] content) {}
}
