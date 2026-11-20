package org.example.aichat.release;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;

@Service
public class Quota {
  private final JdbcTemplate db;private final RuntimeMode mode;
  public Quota(JdbcTemplate db,RuntimeMode mode){this.db=db;this.mode=mode;}
  private String day(){return LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();}
  @Transactional public void reserve(int user,boolean custom){
    if(!mode.cloud())return;String provider=custom?"custom":"platform";int max=mode.number(custom?"daily-custom-limit":"daily-platform-limit",custom?1000:100);
    db.update("INSERT INTO daily_usage(user_id,day,provider) VALUES(?,?,?) ON CONFLICT DO NOTHING",user,day(),provider);
    if(db.update("UPDATE daily_usage SET requests=requests+1 WHERE user_id=? AND day=? AND provider=? AND requests<?",user,day(),provider,max)==0)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"今日试用额度已用完");
  }
  public void record(int user,boolean custom,OpenAiTransport.Usage usage){if(mode.cloud())db.update("UPDATE daily_usage SET input_tokens=input_tokens+?,output_tokens=output_tokens+?,estimated=(estimated OR ?) WHERE user_id=? AND day=? AND provider=?",usage.input(),usage.output(),usage.estimated(),user,day(),custom?"custom":"platform");}
}
