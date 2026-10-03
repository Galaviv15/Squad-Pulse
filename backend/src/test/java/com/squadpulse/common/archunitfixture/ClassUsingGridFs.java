package com.squadpulse.common.archunitfixture;

import com.mongodb.client.gridfs.model.GridFSFile;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;

/**
 * Deliberately violates the GridFS confinement rule: it sits outside {@code common} itself and uses
 * Spring Data's GridFS API and the driver's GridFS model. Used only by {@code
 * GridFsConfinementRuleTest}. Deliberately not a Spring bean, so no test context ever picks it up.
 */
public class ClassUsingGridFs {

  private final GridFsOperations gridFs;

  public ClassUsingGridFs(GridFsOperations gridFs) {
    this.gridFs = gridFs;
  }

  public GridFsResource read(GridFSFile file) {
    return gridFs.getResource(file);
  }
}
