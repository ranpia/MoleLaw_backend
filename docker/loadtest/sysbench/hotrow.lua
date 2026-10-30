function thread_init()
  drv = sysbench.sql.driver()
  con = drv:connect()
end

function event()
  con:query("BEGIN")
  con:query("UPDATE bench_hotspot SET counter=counter+1 WHERE id=1")
  con:query("COMMIT")
end

function thread_done()
  con:disconnect()
end
