package org.ogf.saga.thrift;

import java.util.List;
import java.util.Vector;

import org.ogf.saga.monitoring.Callback;
import org.ogf.saga.monitoring.Metric;
import org.ogf.saga.monitoring.Monitorable;
import org.ogf.saga.error.SagaException;
import org.ogf.saga.context.Context;

class MyCallback implements Callback {
  public List<String> values;

  public MyCallback() {
    values = new Vector<String>();
  }

  public boolean cb(Monitorable m, Metric metric, Context ctxt) {
    try {
      String value = metric.getAttribute(Metric.VALUE);
      values.add(value);
    } catch (SagaException e) {
      System.err.println("error" + e);
      e.printStackTrace(System.err);
    }
    return true;
  }
}

