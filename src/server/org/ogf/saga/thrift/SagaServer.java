// vim: set tw=160
package org.ogf.saga.thrift;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.Enum;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

// log4j
import org.apache.log4j.Logger;

// libthrift.jar
import org.apache.thrift.TException;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.TEnum;

// JavaSAGA
import org.ogf.saga.SagaObject;
import org.ogf.saga.buffer.Buffer;
import org.ogf.saga.buffer.BufferFactory;
import org.ogf.saga.context.Context;
import org.ogf.saga.context.ContextFactory;
import org.ogf.saga.error.*;
import org.ogf.saga.file.Directory;
import org.ogf.saga.file.File;
import org.ogf.saga.file.FileFactory;
import org.ogf.saga.file.IOVec;
import org.ogf.saga.file.SeekMode;
import org.ogf.saga.job.Job;
import org.ogf.saga.job.JobDescription;
import org.ogf.saga.job.JobFactory;
import org.ogf.saga.job.JobService;
import org.ogf.saga.logicalfile.LogicalDirectory;
import org.ogf.saga.logicalfile.LogicalFile;
import org.ogf.saga.logicalfile.LogicalFileFactory;
import org.ogf.saga.monitoring.Callback;
import org.ogf.saga.monitoring.Metric;
import org.ogf.saga.monitoring.Monitorable;
import org.ogf.saga.monitoring.MonitoringFactory;
import org.ogf.saga.namespace.Flags;
import org.ogf.saga.namespace.NSDirectory;
import org.ogf.saga.namespace.NSEntry;
import org.ogf.saga.namespace.NSFactory;
import org.ogf.saga.rpc.Parameter;
import org.ogf.saga.rpc.RPC;
import org.ogf.saga.rpc.RPCFactory;
import org.ogf.saga.session.Session;
import org.ogf.saga.session.SessionFactory;
import org.ogf.saga.stream.Stream;
import org.ogf.saga.stream.StreamInputStream;
import org.ogf.saga.stream.StreamOutputStream;
import org.ogf.saga.stream.StreamServer;
import org.ogf.saga.stream.StreamFactory;
import org.ogf.saga.task.Task;
import org.ogf.saga.task.TaskContainer;
import org.ogf.saga.task.TaskFactory;
import org.ogf.saga.task.State;
import org.ogf.saga.task.TaskMode;
import org.ogf.saga.task.WaitMode;
import org.ogf.saga.url.URL;
import org.ogf.saga.url.URLFactory;

public class SagaServer {
  private static Logger logger = Logger.getLogger(SagaServer.class);

  public static void main(String[] args) {
    try {
      SAGAHandler handler = new SAGAHandler();
      SAGAService.Processor processor = new SAGAService.Processor(handler);

      TServerTransport serverTransport = new TServerSocket(9090);
      //TNonblockingServerSocket serverTransport = new TNonblockingServerSocket(9090);

      /* Thrift <= 0.5
      TServer server = new TThreadPoolServer(processor, serverTransport, new TFramedTransport.Factory(), new TCompactProtocol.Factory());
      */

      // Thrift >= 0.6
      TThreadPoolServer.Args myargs = new TThreadPoolServer.Args(serverTransport);
      myargs.processor(processor);
      //Set different protocol or transport
      //myargs.protocolFactory(new TBinaryProtocol.Factory());
      //myargs.transportFactory(new TFramedTransport.Factory());
      TServer server = new TThreadPoolServer(myargs);

      logger.info("Starting the server...");
      server.serve();
    } catch (Exception x) {
      x.printStackTrace();
    }
    logger.info("done.");
  }

  private static class SAGAHandler implements SAGAService.Iface {
    public final ThreadLocal<ClientState> state = new ThreadLocal<ClientState>()
    {
      @Override
      protected ClientState initialValue() {
        return new ClientState();
      }
    };

    /**************************** Private Helpers *****************************/
    private ClientState getState() {
      return state.get();
    }

    private SeekMode getSeekMode(TSeekMode whence) throws
      InvalidRequestException
    {
      for(SeekMode m: SeekMode.values()) {
        if(m.getValue() == whence.getValue()) {
          return m;
        }
      }
      throw new InvalidRequestException("Invalid SeekMode");
    }

    //return convertEnums(tc.getStates(), TState.class);
    private static <T extends Enum<T>, S extends TEnum> List<S>
      convertEnum(T[] sagaenums, Class<S> thriftenumclass)
      throws InvalidRequestException
    {
      List<S> res = new ArrayList<S>(sagaenums.length);
      for(T sagaenum: sagaenums) {
        res.add(convertEnum(sagaenum, thriftenumclass));
      }
      return res;
    }

    // Converts a SAGA Enum to a Thrift Enum
    private static <T extends Enum<T>, S extends TEnum> S
      convertEnum(T sagaenum, Class<S> thriftenumclass)
      throws InvalidRequestException
    {
      Method m, n;
      try {
        m = thriftenumclass.getMethod("getValue");
      } catch (NoSuchMethodException e) {
        throw new InvalidRequestException("Fatal error: " + e.getMessage());
      }
      try {
        n = sagaenum.getClass().getMethod("getValue");
      } catch (NoSuchMethodException e) {
        throw new InvalidRequestException("Fatal error: " + e.getMessage());
      }
      for(S s: thriftenumclass.getEnumConstants()) {
        Integer val, val2;
        try {
          val = (Integer)m.invoke(s);
        } catch(Exception e) {
          throw new InvalidRequestException("Fatal error: " + e.getMessage());
        }
        try {
          val2 = (Integer)n.invoke(sagaenum);
        } catch(Exception e) {
          throw new InvalidRequestException("Fatal error: " + e.getMessage());
        }
        if(val.compareTo(val2) == 0) {
          return s;
        }
      }
      throw new InvalidRequestException("Could not find SAGA enum " +
          sagaenum + " in Thrift Enum class " + thriftenumclass.getName());
    }

    // Converts a Thrift Enum to a SAGA Enum
    private static <T extends Enum<T>, S extends TEnum> T
      convertEnum(S thriftenum, Class<T> sagaenumclass)
      throws InvalidRequestException
    {
      Method m;
      try {
        m = sagaenumclass.getMethod("getValue");
      } catch (NoSuchMethodException e) {
        throw new InvalidRequestException("Fatal error: " + e.getMessage());
      }
      for(T t: sagaenumclass.getEnumConstants()) {
        Integer val;
        try {
          val = (Integer)m.invoke(t);
        } catch(Exception e) {
          throw new InvalidRequestException("Fatal error: " + e.getMessage());
        }
        if(val == thriftenum.getValue()) {
          return t;
        }
      }
      throw new InvalidRequestException("Could not find Thrift enum " +
          thriftenum + "in SAGA Enum class " + sagaenumclass.getName());
    }

    private <T> List<T> toList(T[] in) {
      return new ArrayList<T>(Arrays.asList(in));
    }

    public void login(String username, String password) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().authenticate(username, password);
    }

    public void free(int oid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      getState().removeSagaObject(oid);
    }

    public int freeAll() throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().removeSagaObjects();
    }

    /***************************** Buffer ************************************/
    @Override
    public int BufferCreate(int size) throws
      TBadParameterException,
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(BufferFactory.createBuffer(size));
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public void BufferClose(int bufferid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      getState().getSagaObject(bufferid, Buffer.class).close();
      getState().removeSagaObject(bufferid);
    }

    @Override
    public ByteBuffer BufferGetData(int bufferid) throws
      TDoesNotExistException,
      TIncorrectStateException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        Buffer b = getState().getSagaObject(bufferid, Buffer.class);
        return ByteBuffer.wrap(b.getData());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      }
    }

    @Override
    public int BufferGetSize(int bufferid) throws
      TIncorrectStateException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(bufferid, Buffer.class).getSize();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      }
    }

    @Override
    public void BufferSetData(int bufferid, ByteBuffer data) throws
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(bufferid, Buffer.class).setData(data.array());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public void BufferSetSize(int bufferid, int size) throws
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(bufferid, Buffer.class).setSize(size);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int BufferClone(int bufferid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Buffer obj = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return getState().addSagaObject((Buffer)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String BufferGetId(int bufferid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(bufferid, Buffer.class).getId();
    }

    @Override
    public int BufferGetSession(int bufferid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Buffer obj = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    /***************************** Context ************************************/
    @Override
    public int ContextCreate(String type) throws
      TIncorrectStateException,
      TNoSuccessException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(ContextFactory.createContext(type));
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean ContextExistsAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).existsAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> ContextFindAttributes(int contextid, List<String> patterns) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Context obj = getState().getSagaObject(contextid, Context.class);
      try {
        return toList(obj.findAttributes(patterns.toArray(new String[0])));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public String ContextGetAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).getAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> ContextGetVectorAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return toList(getState().getSagaObject(contextid, Context.class).getVectorAttribute(key));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean ContextIsReadOnlyAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).isReadOnlyAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean ContextIsRemovableAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).isRemovableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean ContextIsVectorAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).isVectorAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean ContextIsWritableAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(contextid, Context.class).isWritableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> ContextListAttributes(int contextid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Context obj = getState().getSagaObject(contextid, Context.class);
      try {
        return toList(obj.listAttributes());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void ContextRemoveAttribute(int contextid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(contextid, Context.class).removeAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void ContextSetAttribute(int contextid, String key, String value) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(contextid, Context.class).setAttribute(key, value);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    
    @Override
    public void ContextSetVectorAttribute(int contextid, String key, List<String> values) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Context obj = getState().getSagaObject(contextid, Context.class);
      try {
        obj.setVectorAttribute(key, values.toArray(new String[0]));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    /******************************* Directory *******************************/
    @Override
    public int DirectoryCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      Session s = getState().getSagaObject(sessionid, Session.class);
      try {
        return getState().addSagaObject(FileFactory.createDirectory(s, u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      Session s = getState().getSagaObject(sessionid, Session.class);
      Task t;
      try {
        t = FileFactory.createDirectory(convertEnum(mode, TaskMode.class), s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public long DirectoryGetSize(int directoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return d.getSize(u);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryGetSizeAsync(int directoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.getSize(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean DirectoryIsFile(int directoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return d.isFile(u);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryIsFileAsync(int directoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.isFile(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int DirectoryOpenDirectory(int directoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(d.openDirectory(u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryOpenDirectoryAsync(int directoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.openDirectory(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int DirectoryOpenFile(int directoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(d.openFile(u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryOpenFileAsync(int directoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.openFile(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int DirectoryOpenFileInputStream(int directoryid, int urlid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(d.openFileInputStream(u));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryOpenFileInputStreamAsync(int directoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.openFileInputStream(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int DirectoryOpenFileOutputStream(int directoryid, int urlid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(d.openFileOutputStream(u));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryOpenFileOutputStreamAsync(int directoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.openFileOutputStream(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void DirectoryCopy(int directoryid, int sourceid, int targetid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      try {
        d.copy(source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int DirectoryCopyAsync(int directoryid, TTaskMode mode, int sourceid, int targetid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      Task t;
      try {
        t = d.copy(convertEnum(mode, TaskMode.class), source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    public void DirectoryRemove2(int directoryid, String target, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      try {
        d.remove(target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    public void DirectoryRemove(int directoryid, int urlid, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        d.remove(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int DirectoryRemoveAsync(int directoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.remove(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void DirectoryClose(int directoryid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(directoryid, Directory.class).close();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      getState().removeSagaObject(directoryid);
    }

    @Override
    public int DirectoryCloseAsync(int directoryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      Task t;
      try {
        t = d.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String DirectoryGetGroup(int directoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(directoryid, Directory.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String DirectoryGetOwner(int directoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(directoryid, Directory.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void DirectoryPermissionsAllow(int directoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(directoryid, Directory.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean DirectoryPermissionsCheck(int directoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(directoryid, Directory.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void DirectoryPermissionsDeny(int directoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(directoryid, Directory.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<Integer> DirectoryList(int directoryid, String pattern) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      try {
        return getState().addSagaObjects(d.list(pattern));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryListAsync(int directoryid, TTaskMode mode, String pattern) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      try {
        return getState().addSagaObject(d.list(convertEnum(mode, TaskMode.class), pattern));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public boolean DirectoryIsEntry(int directoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return d.isEntry(u);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int DirectoryIsEntryAsync(int directoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Directory d = getState().getSagaObject(directoryid, Directory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = d.isEntry(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    /******************************* File ***********************************/
    @Override
    public int FileCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      Session s = getState().getSagaObject(sessionid, Session.class);
      try {
        return getState().addSagaObject(FileFactory.createFile(s, u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      Session s = getState().getSagaObject(sessionid, Session.class);
      Task t;
      try {
        t = FileFactory.createFile(convertEnum(mode, TaskMode.class), s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public long FileGetSize(int fileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return f.getSize();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileGetSizeAsync(int fileid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return getState().addSagaObject(f.getSize(convertEnum(mode, TaskMode.class)));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public List<String> FileModesE(int fileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return f.modesE();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileRead(int fileid, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.read(b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadAsync(int fileid, TTaskMode mode, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.read(convertEnum(mode, TaskMode.class), b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileReadLen(int fileid, int bufferid, int len) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.read(b, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadLenAsync(int fileid, TTaskMode mode, int bufferid, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.read(convertEnum(mode, TaskMode.class), b, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileReadLenFromOffset(int fileid, int bufferid, int len, int offset) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.read(b, offset, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadLenFromOffsetAsync(int fileid, TTaskMode mode, int bufferid, int offset, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.read(convertEnum(mode, TaskMode.class), b, offset, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileReadE(int fileid, String emode, String spec, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.readE(emode, spec, b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadEAsync(int fileid, TTaskMode mode, String emode, String spec, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.readE(convertEnum(mode, TaskMode.class), emode, spec, b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileReadP(int fileid, String pattern, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.readP(pattern, b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadPAsync(int fileid, TTaskMode mode, String pattern, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.readP(convertEnum(mode, TaskMode.class), pattern, b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    public void FileReadV(int fileid, List<Integer> iovecids) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        f.readV(getState().getSagaObjects(iovecids, IOVec.class));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileReadVAsync(int fileid, TTaskMode mode, List<Integer> iovecids) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Task t;
      try {
        t = f.readV(convertEnum(mode, TaskMode.class), getState().getSagaObjects(iovecids, IOVec.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public long FileSeek(int fileid, long offset, TSeekMode whence) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return f.seek(offset, convertEnum(whence, SeekMode.class));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileSeekAsync(int fileid, TTaskMode mode, long offset, TSeekMode whence) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Task t;
      try {
        t = f.seek(convertEnum(mode, TaskMode.class), offset, convertEnum(whence, SeekMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileSizeE(int fileid, String emode, String spec) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return f.sizeE(emode, spec);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileSizeP(int fileid, String pattern) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        return f.sizeP(pattern);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWrite(int fileid, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.write(b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWriteAsync(int fileid, TTaskMode mode, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.write(convertEnum(mode, TaskMode.class), b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileWriteLen(int fileid, int bufferid, int len) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.write(b, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWriteLenAsync(int fileid, TTaskMode mode, int bufferid, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.write(convertEnum(mode, TaskMode.class), b, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileWriteLenFromOffset(int fileid, int bufferid, int offset, int len) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.write(b, offset, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWriteLenFromOffsetAsync(int fileid, TTaskMode mode, int bufferid, int offset, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.write(convertEnum(mode, TaskMode.class), b, offset, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileWriteE(int fileid, String emode, String spec, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.writeE(emode, spec, b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWriteEAsync(int fileid, TTaskMode mode, String emode, String spec, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.writeE(convertEnum(mode, TaskMode.class), emode, spec, b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int FileWriteP(int fileid, String pattern, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return f.writeP(pattern, b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWritePAsync(int fileid, TTaskMode mode, String pattern, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = f.writeP(convertEnum(mode, TaskMode.class), pattern, b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void FileWriteV(int fileid, List<Integer> iovecids) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      try {
        f.writeV(getState().getSagaObjects(iovecids, IOVec.class));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int FileWriteVAsync(int fileid, TTaskMode mode, List<Integer> iovecids) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Task t;
      try {
        t = f.writeV(convertEnum(mode, TaskMode.class), getState().getSagaObjects(iovecids, IOVec.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String FileGetGroup(int fileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(fileid, File.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String FileGetOwner(int fileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(fileid, File.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void FilePermissionsAllow(int fileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(fileid, File.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean FilePermissionsCheck(int fileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(fileid, File.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void FilePermissionsDeny(int fileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(fileid, File.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void FileClose(int fileid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(fileid, File.class).close();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      getState().removeSagaObject(fileid);
    }

    @Override
    public int FileCloseAsync(int fileid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      File f = getState().getSagaObject(fileid, File.class);
      Task t;
      try {
        t = f.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    /******************************* IOVec ***********************************/
    @Override
    public int IOVecCreate(int size) throws
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(FileFactory.createIOVec(size));
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public int IOVecGetLenIn(int iovecid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(iovecid, IOVec.class).getLenIn();
    }

    @Override
    public int IOVecGetLenOut(int iovecid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(iovecid, IOVec.class).getLenOut();
    }

    @Override
    public int IOVecGetOffset(int iovecid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(iovecid, IOVec.class).getOffset();
    }

    @Override
    public void IOVecSetLenIn(int iovecid, int len) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      IOVec iovec = getState().getSagaObject(iovecid, IOVec.class);
      try {
        iovec.setLenIn(len);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public void IOVecSetOffset(int iovecid, int offset) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      IOVec iovec = getState().getSagaObject(iovecid, IOVec.class);
      try {
        iovec.setOffset(offset);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }
    /************************* JobDescription *********************************/
    @Override
    public int JobDescriptionCreate() throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(JobFactory.createJobDescription());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public boolean JobDescriptionExistsAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).existsAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobDescriptionFindAttributes(int jobdescriptionid, List<String> patterns) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription obj = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        return toList(obj.findAttributes(patterns.toArray(new String[0])));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public String JobDescriptionGetAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).getAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobDescriptionGetVectorAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return toList(getState().getSagaObject(jobdescriptionid, JobDescription.class).getVectorAttribute(key));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobDescriptionIsReadOnlyAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).isReadOnlyAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobDescriptionIsRemovableAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).isRemovableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobDescriptionIsVectorAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).isVectorAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobDescriptionIsWritableAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobdescriptionid, JobDescription.class).isWritableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobDescriptionListAttributes(int jobdescriptionid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription obj = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        return toList(obj.listAttributes());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobDescriptionRemoveAttribute(int jobdescriptionid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobdescriptionid, JobDescription.class).removeAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobDescriptionSetAttribute(int jobdescriptionid, String key, String value) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobdescriptionid, JobDescription.class).setAttribute(key, value);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobDescriptionSetVectorAttribute(int jobdescriptionid, String key, List<String> values) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription obj = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        obj.setVectorAttribute(key, values.toArray(new String[0]));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobDescriptionClone(int jobdescriptionid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription obj = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        return getState().addSagaObject((JobDescription)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String JobDescriptionGetId(int jobdescriptionid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(jobdescriptionid, JobDescription.class).getId();
    }

    @Override
    public int JobDescriptionGetSession(int jobdescriptionid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription obj = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }
    /******************************** JobService ************************************/
    @Override
    public int JobServiceCreateDefault() throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(JobFactory.createJobService());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceCreateDefaultAsync(TTaskMode mode) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t;
      try {
        t = JobFactory.createJobService(convertEnum(mode, TaskMode.class));
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceCreate(int sessionid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(JobFactory.createJobService(s, u));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceCreateAsync(TTaskMode mode, int sessionid, int urlid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = JobFactory.createJobService(convertEnum(mode, TaskMode.class), s, u);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceCreateJob(int jobserviceid, int jobdescriptionid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      JobDescription jd = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        return getState().addSagaObject(js.createJob(jd));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceCreateJobAsync(int jobserviceid, TTaskMode mode, int jobdescriptionid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      JobDescription jd = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      Task t;
      try {
        t = js.createJob(convertEnum(mode, TaskMode.class), jd);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceGetJob(int jobserviceid, String jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      try {
        return getState().addSagaObject(js.getJob(jobid));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceGetJobAsync(int jobserviceid, TTaskMode mode, String jobid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      Task t;
      try {
        t = js.getJob(convertEnum(mode, TaskMode.class), jobid);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceGetSelf(int jobserviceid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      try {
        return getState().addSagaObject(js.getSelf());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceGetSelfAsync(int jobserviceid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      Task t;
      try {
        t = js.getSelf(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public List<String> JobServiceList(int jobserviceid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobserviceid, JobService.class).list();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceListAsync(int jobserviceid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      Task t;
      try {
        t = js.list(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceRunJob(int jobserviceid, String commandline) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      try {
        return getState().addSagaObject(js.runJob(commandline));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceRunJobAsync(int jobserviceid, TTaskMode mode, String commandline) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      Task t;
      try {
        t = js.runJob(convertEnum(mode, TaskMode.class), commandline);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobServiceRunJobOnHost(int jobserviceid, String commandline, String host) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      try {
        return getState().addSagaObject(js.runJob(commandline, host));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobServiceRunJobOnHostAsync(int jobserviceid, TTaskMode mode, String commandline, String host) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobService js = getState().getSagaObject(jobserviceid, JobService.class);
      Task t;
      try {
        t = js.runJob(convertEnum(mode, TaskMode.class), commandline, host);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    /*********************************** Job ************************************/
    @Override
    public void JobCheckpoint(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).checkpoint();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobCheckpointAsync(int jobid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      Task t;
      try {
        t = j.checkpoint(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int JobGetJobDescription(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return getState().addSagaObject(j.getJobDescription());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobGetJobDescriptionAsync(int jobid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      Task t;
      try {
        t = j.getJobDescription(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    /* Can't return a java.io.InputStream
    @Override
    public int JobGetStderr(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return getState().addSagaObject(j.getStderr());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobGetStdin(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return getState().addSagaObject(j.getStdin());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobGetStdout(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return getState().addSagaObject(j.getStdout());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    */

    @Override
    public void JobMigrate(int jobid, int jobdescriptionid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      JobDescription jd = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      try {
        getState().getSagaObject(jobid, Job.class).migrate(jd);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobMigrateAsync(int jobid, TTaskMode mode, int jobdescriptionid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      JobDescription jd = getState().getSagaObject(jobdescriptionid, JobDescription.class);
      Task t;
      try {
        t = j.migrate(convertEnum(mode, TaskMode.class), jd);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void JobResume(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).resume();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobResumeAsync(int jobid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      Task t;
      try {
        t = j.resume(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void JobSignal(int jobid, int signum) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).signal(signum);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobSignalAsync(int jobid, TTaskMode mode, int signum) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      Task t;
      try {
        t = j.signal(convertEnum(mode, TaskMode.class), signum);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void JobSuspend(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).suspend();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobSuspendAsync(int jobid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      Task t;
      try {
        t = j.suspend(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String JobGetGroup(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String JobGetOwner(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobPermissionsAllow(int jobid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobPermissionsCheck(int jobid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void JobPermissionsDeny(int jobid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobExistsAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).existsAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobFindAttributes(int jobid, List<String> patterns) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job obj = getState().getSagaObject(jobid, Job.class);
      try {
        return toList(obj.findAttributes(patterns.toArray(new String[0])));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public String JobGetAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).getAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobGetVectorAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return toList(getState().getSagaObject(jobid, Job.class).getVectorAttribute(key));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobIsReadOnlyAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).isReadOnlyAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobIsRemovableAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).isRemovableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobIsVectorAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).isVectorAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean JobIsWritableAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(jobid, Job.class).isWritableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobListAttributes(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job obj = getState().getSagaObject(jobid, Job.class);
      try {
        return toList(obj.listAttributes());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobRemoveAttribute(int jobid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).removeAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobSetAttribute(int jobid, String key, String value) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(jobid, Job.class).setAttribute(key, value);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    
    @Override
    public void JobSetVectorAttribute(int jobid, String key, List<String> values) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job obj = getState().getSagaObject(jobid, Job.class);
      try {
        obj.setVectorAttribute(key, values.toArray(new String[0]));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobCancel(int jobid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job t = getState().getSagaObject(jobid, Job.class);
      try {
        t.cancel();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobGetResult(int jobid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        j.getResult();
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    /*
    @Override
    public int JobGetObject(int jobid) throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job t = getState().getSagaObject(jobid, Job.class);
      try {
        // NOTE: Who knows the type of this object?? :)
        return getState().addSagaObject((SagaObject)t.getObject());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    */

    @Override
    public void JobRethrow(int jobid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job t = getState().getSagaObject(jobid, Job.class);
      try {
        t.rethrow();
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobRun(int jobid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job t = getState().getSagaObject(jobid, Job.class);
      try {
        t.run();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void JobWaitFor(int jobid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job t = getState().getSagaObject(jobid, Job.class);
      try {
        t.waitFor();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobGetMetric(int jobid, String name) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return getState().addSagaObject(j.getMetric(name));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> JobListMetrics(int jobid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Job j = getState().getSagaObject(jobid, Job.class);
      try {
        return toList(j.listMetrics());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int JobAddCallback(int jobid, String name, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void JobRemoveCallback(int jobid, String name, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }
    /**************************** LogicalDirectory ***************************/
    @Override
    public int LogicalDirectoryCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      LogicalDirectory ld;
      try {
        ld = LogicalFileFactory.createLogicalDirectory(s, u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      return getState().addSagaObject(ld);
    }

    @Override
    public int LogicalDirectoryCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = LogicalFileFactory.createLogicalDirectory(convertEnum(mode, TaskMode.class),
                                                      s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public List<Integer> LogicalDirectoryFind(int logicaldirectoryid, String namepattern, List<String> attrpattern) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      List<URL> res;
      try {
        res = ld.find(namepattern, attrpattern.toArray(new String[0]));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      return getState().addSagaObjects(res);
    }

    @Override
    public int LogicalDirectoryFindAsync(int logicaldirectoryid, TTaskMode mode, String namepattern, List<String> attrpattern) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      Task t;
      try {
        t = ld.find(convertEnum(mode, TaskMode.class), namepattern,
                    attrpattern.toArray(new String[0]));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean LogicalDirectoryIsFile(int logicaldirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return ld.isFile(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    @Override
    public int LogicalDirectoryIsFileAsync(int logicaldirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = ld.isFile(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int LogicalDirectoryOpenLogicalDir(int logicaldirectoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      LogicalDirectory newld;
      try {
        newld = ld.openLogicalDir(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      }
      return getState().addSagaObject(newld);
    }

    @Override
    public int LogicalDirectoryOpenLogicalDirAsync(int logicaldirectoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = ld.openLogicalDir(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int LogicalDirectoryOpenLogicalFile(int logicaldirectoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      LogicalFile lf;
      try {
        lf = ld.openLogicalFile(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      }
      return getState().addSagaObject(lf);
    }

    @Override
    public int LogicalDirectoryOpenLogicalFileAsync(int logicaldirectoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory ld;
      ld = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = ld.openLogicalFile(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String LogicalDirectoryGetGroup(int logicaldirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String LogicalDirectoryGetOwner(int logicaldirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalDirectoryPermissionsAllow(int logicaldirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryPermissionsCheck(int logicaldirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void LogicalDirectoryPermissionsDeny(int logicaldirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryExistsAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).existsAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalDirectoryFindAttributes(int logicaldirectoryid, List<String> patterns) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory obj = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      try {
        return toList(obj.findAttributes(patterns.toArray(new String[0])));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public String LogicalDirectoryGetAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).getAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalDirectoryGetVectorAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return toList(getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).getVectorAttribute(key));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryIsReadOnlyAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).isReadOnlyAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryIsRemovableAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).isRemovableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryIsVectorAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).isVectorAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalDirectoryIsWritableAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).isWritableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalDirectoryListAttributes(int logicaldirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory obj = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      try {
        return toList(obj.listAttributes());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalDirectoryRemoveAttribute(int logicaldirectoryid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).removeAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalDirectorySetAttribute(int logicaldirectoryid, String key, String value) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class).setAttribute(key, value);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    
    @Override
    public void LogicalDirectorySetVectorAttribute(int logicaldirectoryid, String key, List<String> values) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalDirectory obj = getState().getSagaObject(logicaldirectoryid, LogicalDirectory.class);
      try {
        obj.setVectorAttribute(key, values.toArray(new String[0]));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    /******************************* LogicalFile *****************************/
    @Override
    public int LogicalFileCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      LogicalFile lf;
      try {
        lf = LogicalFileFactory.createLogicalFile(s, u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      return getState().addSagaObject(lf);
    }

    @Override
    public int LogicalFileCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = LogicalFileFactory.createLogicalFile(convertEnum(mode, TaskMode.class),
                                                 s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void LogicalFileAddLocation(int logicalfileid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        lf.addLocation(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int LogicalFileAddLocationAsync(int logicalfileid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = lf.addLocation(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public List<Integer> LogicalFileListLocations(int logicalfileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      try {
        return getState().addSagaObjects(lf.listLocations());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int LogicalFileListLocationsAsync(int logicalfileid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      Task t;
      try {
        t = lf.listLocations(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void LogicalFileRemoveLocation(int logicalfileid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        lf.removeLocation(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    @Override
    public int LogicalFileRemoveLocationAsync(int logicalfileid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = lf.removeLocation(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void LogicalFileReplicate(int logicalfileid, int urlid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      try {
        lf.replicate(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    @Override
    public int LogicalFileReplicateAsync(int logicalfileid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = lf.replicate(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void LogicalFileUpdateLocation(int logicalfileid, int nameoldid, int namenewid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL nameold = getState().getSagaObject(nameoldid, URL.class);
      URL namenew = getState().getSagaObject(namenewid, URL.class);
      try {
        lf.updateLocation(nameold, namenew);
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int LogicalFileUpdateLocationAsync(int logicalfileid, TTaskMode mode, int nameoldid, int namenewid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile lf = getState().getSagaObject(logicalfileid, LogicalFile.class);
      URL nameold = getState().getSagaObject(nameoldid, URL.class);
      URL namenew = getState().getSagaObject(namenewid, URL.class);
      Task t;
      try {
        t = lf.updateLocation(convertEnum(mode, TaskMode.class), nameold, namenew);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String LogicalFileGetGroup(int logicalfileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String LogicalFileGetOwner(int logicalfileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalFilePermissionsAllow(int logicalfileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicalfileid, LogicalFile.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFilePermissionsCheck(int logicalfileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalFilePermissionsDeny(int logicalfileid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicalfileid, LogicalFile.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFileExistsAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).existsAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalFileFindAttributes(int logicalfileid, List<String> patterns) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile obj = getState().getSagaObject(logicalfileid, LogicalFile.class);
      try {
        return toList(obj.findAttributes(patterns.toArray(new String[0])));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public String LogicalFileGetAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).getAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalFileGetVectorAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return toList(getState().getSagaObject(logicalfileid, LogicalFile.class).getVectorAttribute(key));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFileIsReadOnlyAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).isReadOnlyAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFileIsRemovableAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).isRemovableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFileIsVectorAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).isVectorAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean LogicalFileIsWritableAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(logicalfileid, LogicalFile.class).isWritableAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> LogicalFileListAttributes(int logicalfileid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile obj = getState().getSagaObject(logicalfileid, LogicalFile.class);
      try {
        return toList(obj.listAttributes());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalFileRemoveAttribute(int logicalfileid, String key) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicalfileid, LogicalFile.class).removeAttribute(key);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void LogicalFileSetAttribute(int logicalfileid, String key, String value) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(logicalfileid, LogicalFile.class).setAttribute(key, value);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    
    @Override
    public void LogicalFileSetVectorAttribute(int logicalfileid, String key, List<String> values) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      LogicalFile obj = getState().getSagaObject(logicalfileid, LogicalFile.class);
      try {
        obj.setVectorAttribute(key, values.toArray(new String[0]));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    /******************************* Metric *************************************/
    @Override
    public int MetricCreate(String name, String desc, String mode, String unit, String type, String value) throws
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Metric m;
      try {
        m = MonitoringFactory.createMetric(name, desc, mode, unit, type, value);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
      return getState().addSagaObject(m);
    }

    @Override
    public int MetricAddCallback(int metricid, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void MetricFire(int metricid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(metricid, Metric.class).fire();
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void MetricRemoveCallback(int metricid, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    /************************** NSDirectory *********************************/
    @Override
    public int NSDirectoryCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(NSFactory.createNSDirectory(u, flags));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = NSFactory.createNSDirectory(convertEnum(mode, TaskMode.class),
                                        s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryChangeDir(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nsd.changeDir(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryChangeDirAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.changeDir(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryCopy(int nsdirectoryid, int sourceid, int targetid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      try {
        nsd.copy(source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryCopyAsync(int nsdirectoryid, TTaskMode mode, int sourceid, int targetid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      Task t;
      try {
        t = nsd.copy(convertEnum(mode, TaskMode.class), source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSDirectoryExists(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return nsd.exists(u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryExistsAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.exists(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public List<Integer> NSDirectoryFind(int nsdirectoryid, String pattern, int flags) 
      throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return getState().addSagaObjects(nsd.find(pattern, flags));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryFindAsync(int nsdirectoryid, TTaskMode mode, String pattern, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      Task t;
      try {
        t = nsd.find(convertEnum(mode, TaskMode.class), pattern, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryGetEntry(int nsdirectoryid, int entry) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return getState().addSagaObject(nsd.getEntry(entry));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryGetEntryAsync(int nsdirectoryid, TTaskMode mode, int entry) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      Task t;
      try {
        t = nsd.getEntry(convertEnum(mode, TaskMode.class), entry);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryGetNumEntries(int nsdirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return nsd.getNumEntries();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryGetNumEntriesAsync(int nsdirectoryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      Task t;
      try {
        t = nsd.getNumEntries(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSDirectoryIsDir(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return nsd.isDir(u);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryIsDirAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.isDir(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSDirectoryIsEntry(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return nsd.isEntry(u);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryIsEntryAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.isEntry(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSDirectoryIsLink(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return nsd.isLink(u);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryIsLinkAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.isLink(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryLink(int nsdirectoryid, int sourceid, int targetid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      try {
        nsd.link(source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryLinkAsync(int nsdirectoryid, TTaskMode mode, int sourceid, int targetid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      Task t;
      try {
        t = nsd.link(convertEnum(mode, TaskMode.class), source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public List<Integer> NSDirectoryList(int nsdirectoryid, String pattern) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return getState().addSagaObjects(nsd.list(pattern));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryListAsync(int nsdirectoryid, TTaskMode mode, String pattern) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return getState().addSagaObject(nsd.list(convertEnum(mode, TaskMode.class), pattern));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public void NSDirectoryMakeDir(int nsdirectoryid, int urlid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nsd.makeDir(u);
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryMakeDirAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.makeDir(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryMove(int nsdirectoryid, int sourceid, int targetid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      try {
        nsd.move(source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryMoveAsync(int nsdirectoryid, TTaskMode mode, int sourceid, int targetid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL source = getState().getSagaObject(sourceid, URL.class);
      URL target = getState().getSagaObject(targetid, URL.class);
      Task t;
      try {
        t = nsd.move(convertEnum(mode, TaskMode.class), source, target, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryOpen(int nsdirectoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(nsd.open(u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryOpenAsync(int nsdirectoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.open(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryOpenDir(int nsdirectoryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(nsd.openDir(u, flags));
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryOpenDirAsync(int nsdirectoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.openDir(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryPermissionsAllow2(int nsdirectoryid, int urlid, String id, int permissions, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nsd.permissionsAllow(u, id, permissions, flags);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryPermissionsAllow2Async(int nsdirectoryid, TTaskMode mode, int urlid, String id, int permissions, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.permissionsAllow(convertEnum(mode, TaskMode.class), u, id, permissions, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryPermissionsDeny2(int nsdirectoryid, int urlid, String id, int permissions, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nsd.permissionsDeny(u, id, permissions, flags);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryPermissionsDeny2Async(int nsdirectoryid, TTaskMode mode, int urlid, String id, int permissions, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.permissionsDeny(convertEnum(mode, TaskMode.class), u, id, permissions, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryReadLink(int nsdirectoryid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(nsd.readLink(u));
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryReadLinkAsync(int nsdirectoryid, TTaskMode mode, int urlid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.readLink(convertEnum(mode, TaskMode.class), u);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSDirectoryRemove(int nsdirectoryid, int urlid, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nsd.remove(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryRemoveAsync(int nsdirectoryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nsd.remove(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String NSDirectoryGetGroup(int nsdirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsdirectoryid, NSDirectory.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String NSDirectoryGetOwner(int nsdirectoryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsdirectoryid, NSDirectory.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void NSDirectoryPermissionsAllow(int nsdirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsdirectoryid, NSDirectory.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean NSDirectoryPermissionsCheck(int nsdirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsdirectoryid, NSDirectory.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void NSDirectoryPermissionsDeny(int nsdirectoryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsdirectoryid, NSDirectory.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void NSDirectoryClose(int nsdirectoryid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsdirectoryid, NSDirectory.class).close();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      getState().removeSagaObject(nsdirectoryid);
    }

    @Override
    public int NSDirectoryCloseAsync(int nsdirectoryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      Task t;
      try {
        t = nsd.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSDirectoryGetURL(int nsdirectoryid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      try {
        return getState().addSagaObject(nsd.getURL());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSDirectoryGetURLAsync(int nsdirectoryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSDirectory nsd = getState().getSagaObject(nsdirectoryid, NSDirectory.class);
      Task t;
      try {
        t = nsd.getURL(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    /***************************** NSEntry ********************************/
    @Override
    public int NSEntryCreate(int sessionid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(NSFactory.createNSEntry(u, flags));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryCreateAsync(TTaskMode mode, int sessionid, int urlid, int flags) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = NSFactory.createNSEntry(convertEnum(mode, TaskMode.class), s, u, flags);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryClose(int nsentryid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsentryid, NSEntry.class).close();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
      getState().removeSagaObject(nsentryid);
    }

    @Override
    public int NSEntryCloseAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryCopy(int nsentryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nse.copy(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryCopyAsync(int nsentryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nse.copy(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSEntryGetCWD(int nsentryid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return getState().addSagaObject(nse.getCWD());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryGetCWDAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.getCWD(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSEntryGetName(int nsentryid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return getState().addSagaObject(nse.getName());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryGetNameAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.getName(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSEntryGetURL(int nsentryid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return getState().addSagaObject(nse.getURL());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryGetURLAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.getURL(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSEntryIsDir(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return nse.isDir();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryIsDirAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.isDir(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSEntryIsEntry(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return nse.isEntry();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryIsEntryAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.isEntry(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public boolean NSEntryIsLink(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return nse.isLink();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryIsLinkAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.isLink(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryLink(int nsentryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nse.link(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryLinkAsync(int nsentryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nse.link(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryMove(int nsentryid, int urlid, int flags) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        nse.move(u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryMoveAsync(int nsentryid, TTaskMode mode, int urlid, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = nse.move(convertEnum(mode, TaskMode.class), u, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryPermissionsAllow2(int nsentryid, String id, int permissions, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        nse.permissionsAllow(id, permissions, flags);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryPermissionsAllow2Async(int nsentryid, TTaskMode mode, String id, int permissions, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.permissionsAllow(convertEnum(mode, TaskMode.class), id, permissions, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryPermissionsDeny2(int nsentryid, String id, int permissions, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        nse.permissionsDeny(id, permissions, flags);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryPermissionsDeny2Async(int nsentryid, TTaskMode mode, String id, int permissions, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.permissionsDeny(convertEnum(mode, TaskMode.class), id, permissions, flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int NSEntryReadLink(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        return getState().addSagaObject(nse.readLink());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryReadLinkAsync(int nsentryid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.readLink(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void NSEntryRemove(int nsentryid, int flags) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      try {
        nse.remove(flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public int NSEntryRemoveAsync(int nsentryid, TTaskMode mode, int flags) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      NSEntry nse = getState().getSagaObject(nsentryid, NSEntry.class);
      Task t;
      try {
        t = nse.remove(convertEnum(mode, TaskMode.class), flags);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public String NSEntryGetGroup(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsentryid, NSEntry.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String NSEntryGetOwner(int nsentryid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsentryid, NSEntry.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void NSEntryPermissionsAllow(int nsentryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsentryid, NSEntry.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean NSEntryPermissionsCheck(int nsentryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(nsentryid, NSEntry.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void NSEntryPermissionsDeny(int nsentryid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(nsentryid, NSEntry.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    /****************************** Parameter *********************************/
    @Override
    public int ParameterCreate() throws
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(RPCFactory.createParameter());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    /******************************* RPC ************************************/
    /*
    @Override
    public int RPCCreate(int sessionid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(RPCFactory.createRPC(s, u));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void RPCCall(int rpcid, List<Integer> parameters) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      RPC obj = getState().getSagaObject(rpcid, RPC.class);
      try {
        obj.call(getState().getSagaObjects(parameters, Parameter.class));
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void RPCClose(int rpcid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(rpcid, RPC.class).close();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }
    
    @Override
    public String RPCGetGroup(int rpcid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(rpcid, RPC.class).getGroup();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public String RPCGetOwner(int rpcid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(rpcid, RPC.class).getOwner();
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void RPCPermissionsAllow(int rpcid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(rpcid, RPC.class).permissionsAllow(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public boolean RPCPermissionsCheck(int rpcid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().getSagaObject(rpcid, RPC.class).permissionsCheck(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    @Override
    public void RPCPermissionsDeny(int rpcid, String id, int permissions) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        getState().getSagaObject(rpcid, RPC.class).permissionsDeny(id, permissions);
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }
    */
    /***************************** Session **********************************/
    @Override
    public int SessionCreate(boolean defaults) throws
      TNoSuccessException,
      // TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(SessionFactory.createSession(defaults));
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      /*
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      */
      }
    }

    @Override
    public void SessionAddContext(int sessionid, int contextid) throws
      TNoSuccessException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      Context c = getState().getSagaObject(contextid, Context.class);
      try {
        s.addContext(c);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void SessionClose(int sessionid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      getState().getSagaObject(sessionid, Session.class).close();
      // TODO: Remove more objects? According to GFD 3.5.2 NOT.
      getState().removeSagaObject(sessionid);
    }

    @Override
    public List<Integer> SessionListContexts(int sessionid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      return getState().addSagaObjects(s.listContexts());
    }

    @Override
    public void SessionRemoveContext(int sessionid, int contextid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      Context c = getState().getSagaObject(contextid, Context.class);
      try {
        s.removeContext(c);
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    /***************************** Stream **********************************/
    @Override
    public int StreamCreate(int sessionid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(StreamFactory.createStream(s, u));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamCreateAsync(TTaskMode mode, int sessionid, int urlid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      Task t;
      try {
        t = StreamFactory.createStream(convertEnum(mode, TaskMode.class), s, u);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void StreamClose(int streamid) throws
      // TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        st.close();
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public int StreamCloseAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void StreamClose2(int streamid, double timeoutInSeconds) throws
      // TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        st.close((float)timeoutInSeconds);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public int StreamClose2Async(int streamid, TTaskMode mode, double timeoutInSeconds) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.close(convertEnum(mode, TaskMode.class), (float)timeoutInSeconds);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public void StreamConnect(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        st.connect();
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamConnectAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.connect(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamGetContext(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addSagaObject(st.getContext());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamGetContextAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.getContext(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamGetInputStream(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addObject(st.getInputStream());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamGetInputStreamAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.getInputStream(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamGetOutputStream(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addObject(st.getOutputStream());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamGetOutputStreamAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.getOutputStream(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamGetURL(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addSagaObject(st.getUrl());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamGetURLAsync(int streamid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.getUrl(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamRead(int streamid, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return st.read(b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamReadLen(int streamid, int bufferid, int len) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return st.read(b, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamReadAsync(int streamid, TTaskMode mode, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = st.read(convertEnum(mode, TaskMode.class), b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamReadLenAsync(int streamid, TTaskMode mode, int bufferid, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = st.read(convertEnum(mode, TaskMode.class), b, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamWaitFor(int streamid, int what) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return st.waitFor(what);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      }
    }

    @Override
    public int StreamWaitFor2(int streamid, int what, double timeoutInSeconds) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return st.waitFor(what, (float)timeoutInSeconds);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      }
    }

    @Override
    public int StreamWaitForAsync(int streamid, TTaskMode mode, int what) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.waitFor(convertEnum(mode, TaskMode.class), what);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamWaitFor2Async(int streamid, TTaskMode mode, int what, double timeoutInSeconds) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Task t;
      try {
        t = st.waitFor(convertEnum(mode, TaskMode.class), what, (float)timeoutInSeconds);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamWrite(int streamid, int bufferid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return st.write(b);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamWriteLen(int streamid, int bufferid, int len) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      try {
        return st.write(b, len);
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamWriteAsync(int streamid, TTaskMode mode, int bufferid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = st.write(convertEnum(mode, TaskMode.class), b);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamWriteLenAsync(int streamid, TTaskMode mode, int bufferid, int len) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      Buffer b = getState().getSagaObject(bufferid, Buffer.class);
      Task t;
      try {
        t = st.write(convertEnum(mode, TaskMode.class), b, len);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamGetMetric(int streamid, String name) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addSagaObject(st.getMetric(name));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> StreamListMetrics(int streamid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream st = getState().getSagaObject(streamid, Stream.class);
      try {
        return toList(st.listMetrics());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamAddCallback(int streamid, String name, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void StreamRemoveCallback(int streamid, String name, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public int StreamClone(int streamid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream obj = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addSagaObject((Stream)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String StreamGetId(int streamid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(streamid, Stream.class).getId();
    }

    @Override
    public int StreamGetSession(int streamid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Stream obj = getState().getSagaObject(streamid, Stream.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }
    /************************** StreamServer *******************************/
    @Override
    public int StreamServerCreate(int sessionid, int urlid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Session s = getState().getSagaObject(sessionid, Session.class);
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        return getState().addSagaObject(StreamFactory.createStreamServer(s, u));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void StreamServerClose(int streamserverid) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        sts.close();
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public void StreamServerClose2(int streamserverid, double timeoutInSeconds) throws
      TNoSuccessException,
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        sts.close((float)timeoutInSeconds);
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
    }

    @Override
    public int StreamServerCloseAsync(int streamserverid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      Task t;
      try {
        t = sts.close(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamServerClose2Async(int streamserverid, TTaskMode mode, double timeoutInSeconds) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      Task t;
      try {
        t = sts.close(convertEnum(mode, TaskMode.class), (float)timeoutInSeconds);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamServerGetURL(int streamserverid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject(sts.getUrl());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamServerGetURLAsync(int streamserverid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      Task t;
      try {
        t = sts.getUrl(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamServerServe(int streamserverid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject(sts.serve());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamServerServe2(int streamserverid, double timeoutInSeconds) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject(sts.serve((float)timeoutInSeconds));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamServerServeAsync(int streamserverid, TTaskMode mode) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      Task t;
      try {
        t = sts.serve(convertEnum(mode, TaskMode.class));
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamServerServe2Async(int streamserverid, TTaskMode mode, double timeoutInSeconds) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      Task t;
      try {
        t = sts.serve(convertEnum(mode, TaskMode.class), (float)timeoutInSeconds);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int StreamServerClone(int streamserverid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer obj = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject((StreamServer)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String StreamServerGetId(int streamserverid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(streamserverid, StreamServer.class).getId();
    }

    @Override
    public int StreamServerGetSession(int streamserverid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer obj = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }
    @Override
    public int StreamServerGetMetric(int streamserverid, String name) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return getState().addSagaObject(sts.getMetric(name));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> StreamServerListMetrics(int streamserverid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      StreamServer sts = getState().getSagaObject(streamserverid, StreamServer.class);
      try {
        return toList(sts.listMetrics());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int StreamServerAddCallback(int streamserverid, String name, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void StreamServerRemoveCallback(int streamserverid, String name, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }
    /********************************* Task **********************************/
    @Override
    public void TaskCancel(int taskid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        t.cancel();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskGetObject(int taskid) throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        // NOTE: Who knows the type of this object?? :)
        return getState().addSagaObject((SagaObject)t.getObject());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public TaskResult TaskGetResult(int taskid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      TaskResult tr = new TaskResult();
      Object o;
      try {
        o = t.getResult();
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
      Class clazz = o.getClass();
      if(String.class == clazz) {
        tr.setResstring((String)o);
      } else if(Integer.class == clazz) {
        tr.setResint((Integer)o);
      } else if(Long.class == clazz) {
        tr.setReslong((Long)o);
      } else if(Boolean.class == clazz) {
        tr.setResboolean((Boolean)o);
      } else if(SagaObject.class.isInstance(o)) {
        tr.setResoid(getState().addSagaObject((SagaObject)o));
      } else if(InputStream.class.isInstance(o) || OutputStream.class.isInstance(o)) {
        tr.setResoid(getState().addObject(o));
      } else if(List.class.isInstance(o)) {
        List l = (List)o;
        Object i = l.get(0);
        if(SagaObject.class.isInstance(i)) {
          tr.setReslistoid(getState().addSagaObjects(l));
        } else if(String.class.isInstance(i)) {
          tr.setResliststring(l);
        } else {
          System.err.println(clazz + " list not found");
        }
      } else if(Void.class == clazz) {
      } else {
        System.err.println(clazz + " not found");
      }
      return tr;
    }

    @Override
    public void TaskRethrow(int taskid) throws
      TAlreadyExistsException,
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TBadParameterException,
      TDoesNotExistException,
      TIncorrectStateException,
      TIncorrectURLException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TSagaIOException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        t.rethrow();
      } catch (AlreadyExistsException e) {
        throw new TAlreadyExistsException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (IncorrectURLException e) {
        throw new TIncorrectURLException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (SagaIOException e) {
        throw new TSagaIOException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskRun(int taskid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        t.run();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskWaitFor(int taskid) throws
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        t.waitFor();
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskGetMetric(int taskid, String name) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task j = getState().getSagaObject(taskid, Task.class);
      try {
        return getState().addSagaObject(j.getMetric(name));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> TaskListMetrics(int taskid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task j = getState().getSagaObject(taskid, Task.class);
      try {
        return toList(j.listMetrics());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskAddCallback(int taskid, String name, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void TaskRemoveCallback(int taskid, String name, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public int TaskClone(int taskid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task obj = getState().getSagaObject(taskid, Task.class);
      try {
        return getState().addSagaObject((Task)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String TaskGetId(int taskid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(taskid, Task.class).getId();
    }

    @Override
    public int TaskGetSession(int taskid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task obj = getState().getSagaObject(taskid, Task.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }

    @Override
    public void TaskRegisterMetric(int taskid, String name) throws
      TSagaException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        MyCallback cb = new MyCallback();
        getState().addCallback(taskid, cb);
        t.addCallback(name, cb);
      } catch (SagaException e) {
        throw new TSagaException(e.getMessage());
      }
    }

    @Override
    public List<String> TaskReadMetric(int taskid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getCallback(taskid).values;
    }

    /***************************** TaskContainer *******************************/
    @Override
    public int TaskContainerCreate() throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(TaskFactory.createTaskContainer());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskContainerAdd(int taskcontainerid, int taskid) throws
      // TAlreadyExistsException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        tc.add(t);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskContainerCancel(int taskcontainerid) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        tc.cancel();
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskContainerCancel2(int taskcontainerid, double timeoutInSeconds) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        tc.cancel((float)timeoutInSeconds);
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<TState> TaskContainerGetStates(int taskcontainerid) throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return convertEnum(tc.getStates(), TState.class);
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerGetTask(int taskcontainerid, String id) throws
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject(tc.getTask(id));
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<Integer> TaskContainerGetTasks(int taskcontainerid) throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObjects(tc.getTasks());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskContainerRemove(int taskcontainerid, int taskid) throws
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      Task t = getState().getSagaObject(taskid, Task.class);
      try {
        tc.remove(t);
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public void TaskContainerRun(int taskcontainerid) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        tc.run();
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerSize(int taskcontainerid) throws
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return tc.size();
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerWaitFor(int taskcontainerid) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject(tc.waitFor());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerWaitFor2(int taskcontainerid, double timeoutInSeconds) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject(tc.waitFor((float)timeoutInSeconds));
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerWaitFor3(int taskcontainerid, double timeoutInSeconds, TWaitMode mode) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      Task t;
      try {
        t = tc.waitFor((float)timeoutInSeconds, convertEnum(mode, WaitMode.class));
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int TaskContainerWaitFor4(int taskcontainerid, TWaitMode mode) throws
      TDoesNotExistException,
      TIncorrectStateException,
      TNoSuccessException,
      TNotImplementedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer tc = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      Task t;
      try {
        t = tc.waitFor(convertEnum(mode, WaitMode.class));
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (IncorrectStateException e) {
        throw new TIncorrectStateException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
      return getState().addSagaObject(t);
    }

    @Override
    public int TaskContainerGetMetric(int taskcontainerid, String name) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TDoesNotExistException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer obj = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject(obj.getMetric(name));
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public List<String> TaskContainerListMetrics(int taskcontainerid) throws
      TAuthenticationFailedException,
      TAuthorizationFailedException,
      TNoSuccessException,
      TNotImplementedException,
      TPermissionDeniedException,
      TTimeoutException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer obj = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return toList(obj.listMetrics());
      } catch (AuthorizationFailedException e) {
        throw new TAuthorizationFailedException(e.getMessage());
      } catch (AuthenticationFailedException e) {
        throw new TAuthenticationFailedException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      } catch (NotImplementedException e) {
        throw new TNotImplementedException(e.getMessage());
      } catch (PermissionDeniedException e) {
        throw new TPermissionDeniedException(e.getMessage());
      } catch (TimeoutException e) {
        throw new TTimeoutException(e.getMessage());
      }
    }

    @Override
    public int TaskContainerAddCallback(int taskcontainerid, String name, int callbackid) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public void TaskContainerRemoveCallback(int taskcontainerid, String name, int cookie) throws
      TNotImplementedException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      throw new TNotImplementedException("Callbacks are not supported");
    }

    @Override
    public int TaskContainerClone(int taskcontainerid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer obj = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject((TaskContainer)obj.clone());
      } catch(CloneNotSupportedException e) {
        throw new InvalidRequestException(e.getMessage());
      }
    }

    @Override
    public String TaskContainerGetId(int taskcontainerid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      return getState().getSagaObject(taskcontainerid, TaskContainer.class).getId();
    }

    @Override
    public int TaskContainerGetSession(int taskcontainerid) throws
      TDoesNotExistException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      TaskContainer obj = getState().getSagaObject(taskcontainerid, TaskContainer.class);
      try {
        return getState().addSagaObject(obj.getSession());
      } catch (DoesNotExistException e) {
        throw new TDoesNotExistException(e.getMessage());
      }
    }
    /******************************* URL ************************************/
    @Override
    public int URLCreate(String name) throws
      TBadParameterException,
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      try {
        return getState().addSagaObject(URLFactory.createURL(name));
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public void URLSetString(int urlid, String name) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setString(name);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    public String URLGetString(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getString();
    }

    public String URLGetEscaped(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getEscaped();
    }

    @Override
    public String URLGetFragment(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getFragment();
    }

    @Override
    public void URLSetFragment(int urlid, String fragment) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setFragment(fragment);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public String URLGetHost(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getHost();
    }

    @Override
    public void URLSetHost(int urlid, String host) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setHost(host);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public String URLGetPath(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getPath();
    }

    @Override
    public void URLSetPath(int urlid, String path) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setPath(path);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public int URLGetPort(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getPort();
    }

    @Override
    public void URLSetPort(int urlid, int port) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setPort(port);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public String URLGetQuery(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getQuery();
    }

    @Override
    public void URLSetQuery(int urlid, String query) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setQuery(query);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public String URLGetScheme(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getScheme();
    }

    @Override
    public void URLSetScheme(int urlid, String scheme) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setScheme(scheme);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public String URLGetUserInfo(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.getUserInfo();
    }

    @Override
    public void URLSetUserInfo(int urlid, String userInfo) throws
      TBadParameterException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      try {
        u.setUserInfo(userInfo);
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      }
    }

    @Override
    public int URLTranslate(int urlid, String scheme) throws
      TBadParameterException,
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      URL u2;
      try {
        return getState().addSagaObject(u.translate(scheme));
      } catch (BadParameterException e) {
        throw new TBadParameterException(e.getMessage());
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    /* TODO: translate with Session */

    @Override
    public int URLResolve(int urlid, int toresolve) throws
      TNoSuccessException,
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      URL u2 = getState().getSagaObject(toresolve, URL.class);
      URL u3;
      try {
        return getState().addSagaObject(u.resolve(u2));
      } catch (NoSuccessException e) {
        throw new TNoSuccessException(e.getMessage());
      }
    }

    @Override
    public boolean URLIsAbsolute(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return u.isAbsolute();
    }

    @Override
    public int URLNormalize(int urlid) throws
      // Always must be thrown
      InvalidRequestException,
      TException
    {
      getState().verifyAuthenticated();
      URL u = getState().getSagaObject(urlid, URL.class);
      return getState().addSagaObject(u.normalize());
    }
  }
}
