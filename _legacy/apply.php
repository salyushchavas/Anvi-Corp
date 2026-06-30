<?php

if(isset($_POST['button']) && isset($_FILES['attachment']))
{
	$from_email		 = 'praveencreativedesigner@gmail.com'; //from mail, sender email address
	$recipient_email = 'praveencreativedesigner@gmail.com'; //recipient email address

	//Load POST data from HTML form
	$sender_name = $_POST["sender_name"]; //sender name
	$reply_to_email = $_POST["sender_email"]; //sender email, it will be used in "reply-to" header
	$subject	 = $_POST["subject"]; //subject for the email
	$message	 = $_POST["message"]; //body of the email

	/*Always remember to validate the form fields like this
	if(strlen($sender_name)<1)
	{
		die('Name is too short or empty!');
	}
	*/
	//Get uploaded file data using $_FILES array
	$tmp_name = $_FILES['attachment']['tmp_name']; // get the temporary file name of the file on the server
	$name	 = $_FILES['attachment']['name']; // get the name of the file
	$size	 = $_FILES['attachment']['size']; // get size of the file for size validation
	$type	 = $_FILES['attachment']['type']; // get type of the file
	$error	 = $_FILES['attachment']['error']; // get the error (if any)

	//validate form field for attaching the file
	if($error > 0)
	{
		die('Upload error or No files uploaded');
	}

	//read from the uploaded file & base64_encode content
	$handle = fopen($tmp_name, "r"); // set the file handle only for reading the file
	$content = fread($handle, $size); // reading the file
	fclose($handle);				 // close upon completion

	$encoded_content = chunk_split(base64_encode($content));
	$boundary = md5("random"); // define boundary with a md5 hashed value

	//header
	$headers = "MIME-Version: 1.0\r\n"; // Defining the MIME version
	$headers .= "From:".$from_email."\r\n"; // Sender Email
	$headers .= "Reply-To: ".$reply_to_email."\r\n"; // Email address to reach back
	$headers .= "Content-Type: multipart/mixed;"; // Defining Content-Type
	$headers .= "boundary = $boundary\r\n"; //Defining the Boundary

	//plain text
	$body = "--$boundary\r\n";
	$body .= "Content-Type: text/plain; charset=ISO-8859-1\r\n";
	$body .= "Content-Transfer-Encoding: base64\r\n\r\n";
	$body .= chunk_split(base64_encode($message));

	//attachment
	$body .= "--$boundary\r\n";
	$body .="Content-Type: $type; name=".$name."\r\n";
	$body .="Content-Disposition: attachment; filename=".$name."\r\n";
	$body .="Content-Transfer-Encoding: base64\r\n";
	$body .="X-Attachment-Id: ".rand(1000, 99999)."\r\n\r\n";
	$body .= $encoded_content; // Attaching the encoded file with email

	$sentMailResult = mail($recipient_email, $subject, $body, $headers);

	if($sentMailResult ){
		echo "<h3>File Sent Successfully.<h3>";
		// unlink($name); // delete the file after attachment sent.
	}
	else{
		die("Sorry but the email could not be sent.
					Please go back and try again!");
	}
}
?>
<!DOCTYPE html>
<html lang="zxx">

<head>
    <meta charset="UTF-8">
    <meta name="description" content="#">
    <meta name="keywords" content="#">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta http-equiv="X-UA-Compatible" content="ie=edge">
    <title>Blueera</title>

    <!-- Google Font -->
<link href="https://fonts.googleapis.com/css2?family=Oswald:wght@200;300;400;500;600;700&family=Roboto:ital,wght@0,100;0,300;0,400;0,500;0,700;0,900;1,100;1,300;1,400;1,500;1,700;1,900&display=swap" rel="stylesheet">
<link href="https://fonts.googleapis.com/css2?family=Raleway:ital,wght@0,100;0,200;0,300;0,400;0,500;0,600;0,700;0,800;0,900;1,100;1,200;1,300;1,400;1,500;1,600;1,700;1,800;1,900&display=swap" rel="stylesheet">

    <!-- Css Styles -->
    <link rel="stylesheet" href="css/bootstrap.min.css" type="text/css">
    <link rel="stylesheet" href="css/font-awesome.min.css" type="text/css">
    <link rel="stylesheet" href="css/owl.carousel.min.css" type="text/css">
    <link rel="stylesheet" href="css/slicknav.min.css" type="text/css">
    <link rel="stylesheet" href="css/style.css" type="text/css">
<style>
h3 {
    font-size: 18px;
    /* padding: 15px; */
    color: green;
    font-weight: bold;
}
</style>
</head>

<body>
    <!-- Page Preloder -->
    <div id="preloder">
        <div class="loader"></div>
    </div>


    <header class="header inner-page">
        <div class="container">
            <div class="row">
                <div class="col-lg-3">
                    <div class="header__logo">
                        <a href="./index.html"><img src="img/logo-3.png" /></a>
                    </div>
                </div>
                <div class="col-lg-9">
                    <div class="header__nav__option">
                      <nav class="header__nav__menu mobile-menu">
                          <ul>

                              <li><a href="index.html">Home</a></li>
                              <li><a href="#">Industries</a>
                                  <ul class="dropdown">
                                          <li><a>IT Industry</a></li>
                                          <li><a>Finance</a></li>
                                          <li><a>Healthcare</a></li>
                                          <li><a>Telecommunications</a></li>
                                          <li><a>Marketing</a></li>
                                          <li><a>Media & Entertainment</a></li>
                                          <li><a>Data & Analytics</a></li>
                                          <li><a>Human Resources</a></li>
                                          <li><a>Education & E-learning</a></li>
                                          <li><a>Cloud Infrastructure</a></li>


                                  </ul>
                              </li>
                              <li><a href="#">Services</a>
                                  <ul class="dropdown">
                                      <li><a>Software Development</a></li>
                                      <li><a>Consulting Services</a></li>
                                      <li><a>Blockchain Development</a></li>
                                      <li><a>Cloud Computing Services</a></li>
                                      <li><a href="services.html" target="_blank">Mobile App Development</a></li>
                                      <li><a>Web Development</a></li>
                                      <li><a>Data Analytics and Business Intelligence</a></li>
                                      <li><a>Cybersecurity Solutions</a></li>
                                      <li><a>Artificial Intelligence Solutions</a></li>
                                      <li><a>Internet of Things Integration</a></li>
                                      <li><a>Digital Marketing Services</a></li>
                                      <li><a>UI/UX Design</a></li>
                                      <li><a>E-commerce Solutions</a></li>
                                      <li><a>Augmented Reality and Virtual Reality Development</a></li>
                                      <li><a>IT Infrastructure Managemen</a></li>


                                  </ul>
                              </li>
															<li><a href="#">Resources</a>
                                  <ul class="dropdown">
                                      <li><a href="about.html">About Us</a></li>
                                      <li><a href="#">Blog</a></li>
                                      <li><a href="contact.php">Contact Us</a></li>
                                      <li><a href="careers.html">Careers</a></li>
                                  </ul>
                              </li>

                              <li><a href="job-listing.html" class="apply-btn" target="_blank">Join Our Team Today! </a></li>
                          </ul>
                      </nav>

                    </div>
                </div>
            </div>
            <div id="mobile-menu-wrap"></div>
        </div>
    </header>

  <section class="page-title bg-1 about-banner">
    <div class="overlay"></div>
    <div class="container">
      <div class="row">
        <div class="col-md-12">
          <div class="block text-center">

            <h1 class="text-capitalize mb-5 text-lg">Create your future, today.</h1>

          </div>
        </div>
      </div>
    </div>
  </section>



  <section class="spad contact-us" style="padding-bottom:0;">



      <div class="container">
        <div class="row">

          <div class="contact-info" style="padding-top:5rem;">
            <div class="contact-info-item">
              <div class="contact-info-icon">
                <i class="fa fa-map-marker"></i>
              </div>

              <div class="contact-info-content">
                <h4>Address</h4>
                <p>Blueera Technologies, Inc.<br/>
6010 W Spring Creek Pkwy,<br/>
Plano, TX – 75024, U.S.A.</p>
              </div>
            </div>

            <div class="contact-info-item">
              <div class="contact-info-icon">
                <i class="fa fa-phone"></i>
              </div>

              <div class="contact-info-content">
                <h4>Phone</h4>
                <p>469-287-5422</p>
              </div>
            </div>

            <div class="contact-info-item">
              <div class="contact-info-icon">
                <i class="fa fa-envelope"></i>
              </div>

              <div class="contact-info-content">
                <h4>Email</h4>
               <p>info@blueera.com</p>
              </div>
            </div>
          </div>

          <div class="contact-form">
            <!--<form action="" id="contact-form">
              <h2>Connect With Us</h2>
              <div class="input-box">
                <input type="text" required="true" name="">
                <span>Full Name</span>
              </div>

              <div class="input-box">
                <input type="email" required="true" name="">
                <span>Email</span>
              </div>

              <div class="input-box">
                <textarea required="true" name=""></textarea>
                <span>Type your Message...</span>
              </div>

              <div class="input-box">
                <input type="submit" value="Send Message" name="">
              </div>
            </form>-->


            <form enctype="multipart/form-data" method="POST" action="">
        			<div class="form-group">
        				<input class="form-control" type="text" name="sender_name" placeholder="Your Name" required/>
        			</div>
        			<div class="form-group">
        				<input class="form-control" type="email" name="sender_email" placeholder="Recipient's Email Address" required/>
        			</div>
        			<div class="form-group">
        				<input class="form-control" type="text" name="subject" placeholder="Subject"/>
        			</div>
        			<div class="form-group">
        				<textarea class="form-control" name="message" placeholder="Message"></textarea>
        			</div>
        			<div class="form-group">
        				<input class="form-control attachment" type="file" name="attachment" placeholder="Attachment" required/>
        			</div>
        			<div class="form-group">
        				<input class="btn apply-btn" type="submit" name="button" value="Submit" />
        			</div>
        		</form>
          </div>

        </div>
      </div>
    </section>



    <div class="section cta-section-02 section-padding-02">
                <div class="container">
                    <!-- Cta Wrap Start -->
                    <div class="cta-wrap" style="background-image: url(img/cta-bg.jpg);">
                        <div class="row align-items-center">
                            <div class="col-xl-9 col-lg-8">
                                <!-- Cta Content Start -->
                                <div class="cta-content">
                                    <div class="cta-icon">
                                        <img src="img/cta-icon2.png" alt="">
                                    </div>
                                    <p>We’re delivering the best customer experience</p>
                                </div>
                                <!-- Cta Content End -->
                            </div>
                            <div class="col-xl-3 col-lg-4">
                                <!-- Cta Button Start -->
                                <div class="cta-btn">
                                    <a class="btn btn-white" href="#">469-287-5422</a>
                                </div>
                                <!-- Cta Button End -->
                            </div>
                        </div>
                    </div>
                    <!-- Cta Wrap End -->
                </div>
            </div>



    <!-- Footer Section Begin -->
    <footer class="footer">
        <div class="container">
            <div class="footer__top">
              <span class="c-footer__text-loop full-width">
      		<span class="anim--loop">BLUEERA, WHERE IDEOLOGY MEETS TECHNOLOGY.&nbsp;BLUEERA, WHERE IDEOLOGY MEETS TECHNOLOGY.&nbsp;</span>
      	</span>
            </div>
            <div class="footer__option">
                <div class="row">
                    <div class="col-lg-3 col-md-6 col-sm-6">
                        <div class="footer__option__item">
                            <h5 style="font-size:40px;">Blueera</h5>

                        </div>
                    </div>
                    <div class="col-lg-4 col-md-3 col-sm-3">
                        <div class="footer__option__item">
                            <!--<h5>Services</h5>-->
                            <ul>
                                <li><a href="#">Software Development</a></li>
                                <li><a href="#">Consulting Services</a></li>
                                <li><a href="#">Blockchain Development</a></li>
                                <li><a href="#">Cloud Computing Services</a></li>
                                <li><a href="services.html" target="_blank">Mobile App Development</a></li>

                            </ul>
                        </div>
                    </div>
                    <div class="col-lg-2 col-md-3 col-sm-3">
                        <div class="footer__option__item">
                            <!--<h5>industries</h5>-->
														<ul>
																<li><a href="about.html">About Us</a></li>
																<li><a href="contact.php">Contact Us</a></li>
																<li><a href="#">Blog</a></li>
																<li><a href="careers.html">Careers</a></li>
														</ul>
                        </div>
                    </div>
                    <div class="col-lg-3 col-md-12">
                      <div class="footer__top__social">
                        <a href="https://www.instagram.com/blueera_technologies/" target="_blank"><i class="fa fa-instagram"></i></a>
                        <a href="https://www.linkedin.com/company/blueera-technologies-inc" target="_blank"><i class="fa fa-linkedin"></i></a>
                          <a href="https://www.facebook.com/blueeratechnologies" target="_blank"><i class="fa fa-facebook"></i></a>
                          <a href="https://twitter.com/blueera_inc" target="_blank"><i class="fa fa-twitter"></i></a>
                      </div>
                    </div>
                </div>
            </div>
            <div class="footer__copyright">
                <div class="row">
                    <div class="col-lg-12 text-center">
                        <!-- Link back to Colorlib can't be removed. Template is licensed under CC BY 3.0. -->
                        <p class="footer__copyright__text">Blueera &copy; 2023. All rights reserved.

                        </p>
                        <!-- Link back to Colorlib can't be removed. Template is licensed under CC BY 3.0. -->
                    </div>
                </div>
            </div>
        </div>
    </footer>
    <!-- Footer Section End -->

    <!-- Js Plugins -->
    <script src="js/jquery-3.3.1.min.js"></script>
    <script src="js/bootstrap.min.js"></script>
    <script src="js/masonry.pkgd.min.js"></script>
    <script src="js/jquery.slicknav.js"></script>
    <script src="js/owl.carousel.min.js"></script>
    <script src="js/main.js"></script>
    <script>
    let resizeReset = function() {
      w = canvasBody.width = window.innerWidth;
      h = canvasBody.height = window.innerHeight;
    }

    const opts = {
      particleColor: "rgb(0,184,207)",
      lineColor: "rgb(0,184,207)",
      particleAmount: 80,
      defaultSpeed: 1,
      variantSpeed: 1,
      defaultRadius: 2,
      variantRadius: 2,
      linkRadius: 200,
    };

    window.addEventListener("resize", function(){
      deBouncer();
    });

    let deBouncer = function() {
        clearTimeout(tid);
        tid = setTimeout(function() {
            resizeReset();
        }, delay);
    };

    let checkDistance = function(x1, y1, x2, y2){
      return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    };

    let linkPoints = function(point1, hubs){
      for (let i = 0; i < hubs.length; i++) {
        let distance = checkDistance(point1.x, point1.y, hubs[i].x, hubs[i].y);
        let opacity = 1 - distance / opts.linkRadius;
        if (opacity > 0) {
          drawArea.lineWidth = 0.5;
          drawArea.strokeStyle = `rgba(${rgb[0]}, ${rgb[1]}, ${rgb[2]}, ${opacity})`;
          drawArea.beginPath();
          drawArea.moveTo(point1.x, point1.y);
          drawArea.lineTo(hubs[i].x, hubs[i].y);
          drawArea.closePath();
          drawArea.stroke();
        }
      }
    }

    Particle = function(xPos, yPos){
      this.x = Math.random() * w;
      this.y = Math.random() * h;
      this.speed = opts.defaultSpeed + Math.random() * opts.variantSpeed;
      this.directionAngle = Math.floor(Math.random() * 360);
      this.color = opts.particleColor;
      this.radius = opts.defaultRadius + Math.random() * opts. variantRadius;
      this.vector = {
        x: Math.cos(this.directionAngle) * this.speed,
        y: Math.sin(this.directionAngle) * this.speed
      };
      this.update = function(){
        this.border();
        this.x += this.vector.x;
        this.y += this.vector.y;
      };
      this.border = function(){
        if (this.x >= w || this.x <= 0) {
          this.vector.x *= -1;
        }
        if (this.y >= h || this.y <= 0) {
          this.vector.y *= -1;
        }
        if (this.x > w) this.x = w;
        if (this.y > h) this.y = h;
        if (this.x < 0) this.x = 0;
        if (this.y < 0) this.y = 0;
      };
      this.draw = function(){
        drawArea.beginPath();
        drawArea.arc(this.x, this.y, this.radius, 0, Math.PI*2);
        drawArea.closePath();
        drawArea.fillStyle = this.color;
        drawArea.fill();
      };
    };

    function setup(){
      particles = [];
      resizeReset();
      for (let i = 0; i < opts.particleAmount; i++){
        particles.push( new Particle() );
      }
      window.requestAnimationFrame(loop);
    }

    function loop(){
      window.requestAnimationFrame(loop);
      drawArea.clearRect(0,0,w,h);
      for (let i = 0; i < particles.length; i++){
        particles[i].update();
        particles[i].draw();
      }
      for (let i = 0; i < particles.length; i++){
        linkPoints(particles[i], particles);
      }
    }

    const canvasBody = document.getElementById("canvas"),
    drawArea = canvasBody.getContext("2d");
    let delay = 200, tid,
    rgb = opts.lineColor.match(/\d+/g);
    resizeReset();
    setup();
    </script>

</body>

</html>
