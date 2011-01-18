package org.ogf.saga.thrift;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// JavaSAGA
import org.ogf.saga.SagaObject;
import org.ogf.saga.monitoring.Callback;


class ClientState {
  private ObjectStore<MyCallback> cbos;
  private ObjectStore<SagaObject> soos;
  private ObjectStore<Object> oos;
  private static Map<String, String> creds = new HashMap<String, String>();
  private boolean authenticated = false;

  ClientState() {
    loadCreds();
    cbos = new ObjectStore<MyCallback>(128, "Callback");
    soos = new ObjectStore<SagaObject>(256, "SagaObject");
    oos = new ObjectStore<Object>(128, "Object");
  }

  private class ObjectStore<T> {
    private Map<Integer, T> objs = new HashMap<Integer, T>();
    private AtomicInteger index = new AtomicInteger();
    private int limit;
    private String desc;

    public ObjectStore(int limit, String desc) {
      this.limit = limit;
      this.desc = desc;
    }

    private void add_(int index, T t) throws InvalidRequestException {
      checkSize();
      objs.put(new Integer(index), t);
    }

    private T get_(int objid) throws InvalidRequestException {
      checkIndex(objid);
      return objs.get(new Integer(objid));
    }

    private void remove_(int objid) throws InvalidRequestException {
      checkIndex(objid);
      objs.remove(new Integer(objid));
    }

    private int getSize() {
      return objs.size();
    }

    private void checkSize() throws InvalidRequestException {
      if(getSize() >= limit) {
        throw new InvalidRequestException(desc + " limit " + limit + " exceeded");
      }
    }

    private int getFreeIndex() {
      return index.getAndIncrement();
    }

    private void checkIndex(int objid) throws InvalidRequestException {
      if(! objs.containsKey(new Integer(objid))) {
        throw new InvalidRequestException("Invalid " + desc + " id " + objid);
      }
    }


    public void add(int index, T t) throws InvalidRequestException {
      add_(index, t);
    }

    public int add(T t) throws InvalidRequestException {
      int index = getFreeIndex();
      add_(index, t);
      return index;
    }

    public List<Integer> add(List<T> in) throws InvalidRequestException {
      List<Integer> res = new ArrayList<Integer>(in.size());
      for(T t: in) {
        res.add(add(t));
      }
      return res;
    }

    public T get(int objid) throws InvalidRequestException
    {
      return get_(objid);
    }

    public <S> S getAndCast(int objid, Class<S> type) throws InvalidRequestException
    {
      T t = get_(objid);
      try {
        return type.cast(t);
      } catch (ClassCastException e) {
        throw new InvalidRequestException(desc + " id " + objid + " not a valid " + type.getName());
      }
    }

    public List<T> get(List<Integer> objids) throws InvalidRequestException
    {
      List<T> res = new ArrayList<T>();
      for(Integer objid: objids) {
        res.add(get_(objid.intValue()));
      }
      return res;
    }

    /*
    protected Map<Integer, T> get()
    {
      Map<Integer, T> res = getAll();
      return objs;
    }
    */

    public void remove(int objid) throws InvalidRequestException
    {
      remove_(objid);
    }

    public void remove(int[] objids) throws InvalidRequestException
    {
      for(int i: objids) {
        remove_(i);
      }
    }

    public int size() {
      return getSize();
    }

    public int remove()
    {
      int numobjs = size();
      objs.clear();
      return numobjs;
    }

  }

  /****** CALLBACKS *******/
  protected void addCallback(int objid, MyCallback cb) throws InvalidRequestException {
    cbos.add(objid, cb);
  }

  protected MyCallback getCallback(int objid) throws InvalidRequestException {
    return cbos.get(objid);
  }

  protected void removeCallback(int objid) throws InvalidRequestException {
    cbos.remove(objid);
  }

  /****** SAGAOBJECTS *******/
  protected int addSagaObject(SagaObject so) throws InvalidRequestException {
    return soos.add(so);
  }

  protected int addObject(Object o) throws InvalidRequestException {
    return oos.add(o);
  }

  protected <T extends SagaObject> List<Integer> addSagaObjects(List<T> in) throws InvalidRequestException {
    List<Integer> res = new ArrayList<Integer>(in.size());
    for(T so: in) {
      res.add(addSagaObject(so));
    }
    return res;
  }

  protected <T extends SagaObject> List<Integer> addSagaObjects(T[] in) throws InvalidRequestException {
    return addSagaObjects(new ArrayList<T>(Arrays.asList(in)));
  }

  protected <T extends SagaObject> T getSagaObject(int objid, Class<T> sagatype) throws InvalidRequestException
  {
    return soos.getAndCast(objid, sagatype);
  }

  protected <T> T getObject(int objid, Class<T> type) throws InvalidRequestException
  {
    return oos.getAndCast(objid, type);
  }

  @SuppressWarnings("unchecked")
  protected <T extends SagaObject> T[] getSagaObjects(List<Integer> objids, Class<T> sagatype) throws InvalidRequestException
  {
    T[] res = (T[]) Array.newInstance(sagatype, objids.size());
    int i = 0;
    for(Integer objid: objids) {
      res[i++] = getSagaObject(objid.intValue(), sagatype);
    }
    return res;
  }

  /*
  protected Map<Integer, SagaObject> getSagaObjects()
  {
    return soos.get();
  }
  */

  protected void removeSagaObject(int objid) throws InvalidRequestException
  {
    soos.remove(objid);
  }

  protected void removeObject(int objid) throws InvalidRequestException
  {
    oos.remove(objid);
  }

  protected void removeSagaObjects(int[] objids) throws InvalidRequestException
  {
    for(int i: objids) {
      removeSagaObject(i);
    }
  }

  protected int removeSagaObjects()
  {
    return soos.remove();
  }

  protected void authenticate(String username, String password) throws
      InvalidRequestException {
    if(! creds.containsKey(username) ) {
      throw new InvalidRequestException("Wrong username and/or password");
    }
    if(! creds.get(username).equals(password)) {
      throw new InvalidRequestException("Wrong username and/or password");
    }
    authenticated = true;
  }

  protected void verifyAuthenticated() throws InvalidRequestException {
    if(! authenticated) {
      throw new InvalidRequestException("Not authenticated");
    }
  }

  private void loadCreds() {
    // TODO: config file?
    creds.put("fritz", "hubert");
    creds.put("admin", "password");
    creds.put("livewire", "infinity");
  }

}
