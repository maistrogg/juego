
var express = require("express");
var app = express();
var http = require("http").Server(app);
var io = require("./libs/game_manager").listen(http);  

app.set("port", (process.env.PORT || 3001));  
app.use(express.static("public")); 


app.get("/", function(req, res) {
	
});


app.get("*", function(req, res) {
	res.status(404).send("Error 404 - Page not found");
});


http.listen(app.get("port"), function() {
	console.log("Node app started on port %s", app.get("port"));
});
